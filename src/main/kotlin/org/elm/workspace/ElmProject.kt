package org.elm.workspace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.intellij.openapi.vfs.LocalFileSystem
import org.elm.lang.core.moduleLookupHack
import org.elm.workspace.ElmToolchain.Companion.ELM_LEGACY_JSON
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths


private val objectMapper = ObjectMapper()

/**
 * The logical representation of an Elm project. An Elm project can be an application
 * or a package, and it specifies its dependencies.
 *
 * @param manifestPath The location of the manifest file (e.g. `elm.json`). Uniquely identifies a project.
 * @param dependencies Additional Elm packages that this project depends on
 * @param testDependencies Additional Elm packages that this project's **tests** depends on
 * @param sourceDirectories The paths to one-or-more directories containing Elm source files belonging to this project.
 */
sealed class ElmProject(
        val manifestPath: Path,
        val dependencies: List<ElmPackageProject>,
        val testDependencies: List<ElmPackageProject>,
        val sourceDirectories: List<Path>
) {

    /**
     * The path to the directory containing the Elm project JSON file.
     */
    val projectDirPath: Path
        get() =
            manifestPath.parent


    /**
     * A name which can be shown in the UI. Note that while Elm packages have user-assigned
     * names, applications do not. Thus, in order to cover both cases, we use the name
     * of the parent directory.
     */
    val presentableName: String
        get() = projectDirPath.fileName.toString()


    /**
     * Returns all packages which this project depends on, whether it be for normal,
     * production code or for tests.
     */
    val allResolvedDependencies: Sequence<ElmPackageProject>
        get() = sequenceOf(dependencies, testDependencies).flatten()


    companion object {

        fun parse(manifestPath: Path, toolchain: ElmToolchain): ElmProject {
            val inputStream = LocalFileSystem.getInstance().refreshAndFindFileByPath(manifestPath.toString())?.inputStream
                    ?: throw ProjectLoadException("Could not find file $manifestPath")
            return parse(inputStream, manifestPath, toolchain)
        }

        /**
         * Attempts to parse an `elm.json` file.
         *
         * @throws ProjectLoadException if the JSON cannot be parsed
         */
        fun parse(inputStream: InputStream, manifestPath: Path, toolchain: ElmToolchain): ElmProject {
            if (manifestPath.endsWith(ELM_LEGACY_JSON)) {
                // Handle legacy Elm 0.18 package. We don't need to model the dependencies
                // because Elm 0.18 stored everything in a local `elm-stuff` directory, and we assume
                // that the user has not excluded that directory from the project.
                return ElmApplicationProject(
                        manifestPath = manifestPath,
                        elmVersion = Version(0, 18, 0),
                        dependencies = emptyList(),
                        testDependencies = emptyList(),
                        sourceDirectories = emptyList()
                )
            }

            val node = try {
                objectMapper.readTree(inputStream)
            } catch (e: JsonProcessingException) {
                throw ProjectLoadException("Bad JSON: ${e.message}")
            }
            val type = node.get("type")?.textValue()
            return when (type) {
                "application" -> {
                    val dto = try {
                        objectMapper.treeToValue(node, ElmApplicationProjectDTO::class.java)
                    } catch (e: JsonProcessingException) {
                        throw ProjectLoadException("Invalid elm.json: ${e.message}")
                    }
                    ElmApplicationProject(
                            manifestPath = manifestPath,
                            elmVersion = dto.elmVersion,
                            dependencies = dto.dependencies.depsToPackages(toolchain),
                            testDependencies = dto.testDependencies.depsToPackages(toolchain),
                            sourceDirectories = dto.sourceDirectories
                    )
                }
                "package" -> {
                    val dto = try {
                        objectMapper.treeToValue(node, ElmPackageProjectDTO::class.java)
                    } catch (e: JsonProcessingException) {
                        throw ProjectLoadException("Invalid elm.json: ${e.message}")
                    }
                    // TODO [kl] resolve dependency constraints to determine package version numbers
                    // [x] use whichever version number is available in the Elm package cache (~/.elm)
                    // [ ] include transitive dependencies
                    // [ ] resolve versions such that all constraints are satisfied
                    //     (necessary for correctness sake, but low priority)
                    ElmPackageProject(
                            manifestPath = manifestPath,
                            elmVersion = dto.elmVersion,
                            dependencies = dto.dependencies.constraintDepsToPackages(toolchain),
                            testDependencies = dto.testDependencies.constraintDepsToPackages(toolchain),
                            sourceDirectories = listOf(Paths.get("src")),
                            name = dto.name,
                            version = dto.version,
                            exposedModules = dto.exposedModulesNode.toExposedModuleMap())
                }
                else -> throw ProjectLoadException("The 'type' field is '$type', "
                        + "but expected either 'application' or 'package'")
            }
        }
    }
}


/**
 * Represents an Elm application
 */
class ElmApplicationProject(
        manifestPath: Path,
        val elmVersion: Version,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories)


/**
 * Represents an Elm package/library
 */
class ElmPackageProject(
        manifestPath: Path,
        val elmVersion: Constraint,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>,
        val name: String,
        val version: Version,
        val exposedModules: List<String>
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories)


private fun ExactDependenciesDTO.depsToPackages(toolchain: ElmToolchain) =
        direct.depsToPackages(toolchain) + indirect.depsToPackages(toolchain)


private fun Map<String, Version>.depsToPackages(toolchain: ElmToolchain) =
        map { (name, version) ->
            loadPackage(toolchain, name, version)
        }

private fun Map<String, Constraint>.constraintDepsToPackages(toolchain: ElmToolchain) =
        map { (name, constraint) ->
            val version = toolchain.availableVersionsForPackage(name)
                    .filter { constraint.contains(it) }
                    .sorted()
                    .firstOrNull()
                    ?: throw ProjectLoadException("Could not load $name ($constraint)")

            loadPackage(toolchain, name, version)
        }

fun loadPackage(toolchain: ElmToolchain, name: String, version: Version): ElmPackageProject {
    val manifestPath = toolchain.findPackageManifest(name, version)
            ?: throw ProjectLoadException("Could not load $name ($version): manifest not found")
    // TODO [kl] guard against circular dependencies
    val elmProject = ElmProject.parse(manifestPath, toolchain) as? ElmPackageProject
            ?: throw ProjectLoadException("Could not load $name ($version): expected a package!")

    return elmProject
}


/**
 * A dummy sentinel value because [LightDirectoryIndex] needs it.
 */
val noProjectSentinel = ElmApplicationProject(
        manifestPath = Paths.get("/elm.json"),
        elmVersion = Version(0, 0, 0),
        dependencies = emptyList(),
        testDependencies = emptyList(),
        sourceDirectories = emptyList()
)


// JSON Decoding


@JsonIgnoreProperties(ignoreUnknown = true)
private interface ElmProjectDTO


private class ElmApplicationProjectDTO(
        @JsonProperty("elm-version") val elmVersion: Version,
        @JsonProperty("source-directories") val sourceDirectories: List<Path>,
        @JsonProperty("dependencies") val dependencies: ExactDependenciesDTO,
        @JsonProperty("test-dependencies") val testDependencies: ExactDependenciesDTO
) : ElmProjectDTO


private class ExactDependenciesDTO(
        @JsonProperty("direct") val direct: Map<String, Version>,
        @JsonProperty("indirect") val indirect: Map<String, Version>
)


private class ElmPackageProjectDTO(
        @JsonProperty("elm-version") val elmVersion: Constraint,
        @JsonProperty("dependencies") val dependencies: Map<String, Constraint>,
        @JsonProperty("test-dependencies") val testDependencies: Map<String, Constraint>,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: Version,
        @JsonProperty("exposed-modules") val exposedModulesNode: JsonNode
) : ElmProjectDTO


private fun JsonNode.toExposedModuleMap(): List<String> {
    // Normalize the 2 exposed-modules formats into a single format.
    // format 1: a list of strings, where each string is the name of an exposed module
    // format 2: a map where the keys are categories and the values are the names of the modules
    //           exposed in that category. We discard the categories because they are not useful.
    return when (this.nodeType) {
        JsonNodeType.ARRAY -> {
            this.elements().asSequence().map { moduleLookupHack(it.textValue()) }.toList()
        }
        JsonNodeType.OBJECT -> {
            this.fields().asSequence().flatMap { (_, nameNodes) ->
                nameNodes.asSequence().map { moduleLookupHack(it.textValue()) }
            }.toList()
        }
        else -> {
            throw RuntimeException("exposed-modules JSON must be either an array or an object")
        }
    }
}
