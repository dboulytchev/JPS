package org.jetbrains.jps.idea

import org.jetbrains.jps.MacroExpander
import org.jetbrains.jps.Project
import org.jetbrains.jps.artifacts.*

 /**
 * @author nik
 */
class ArtifactLoader {
  private final Project project
  private final MacroExpander macroExpander
  private static OwnServiceLoader<LayoutElementTypeService> elementTypeLoader = OwnServiceLoader.load(LayoutElementTypeService.class)
  private static OwnServiceLoader<ArtifactPropertiesProviderService> propertiesProvidersLoader = OwnServiceLoader.load(ArtifactPropertiesProviderService.class)
  private static Map<String, LayoutElementTypeService> elementTypes = null
  private static Map<String, ArtifactPropertiesProviderService> propertiesProviders = null

  def ArtifactLoader(Project project, MacroExpander macroExpander) {
    this.macroExpander = macroExpander
    this.project = project
  }

  LayoutElement loadLayoutElement(Node tag, String artifactName) {
    String id = tag."@id";
    switch (id) {
      case "root":
        return new RootElement(loadChildren(tag, artifactName));
      case "directory":
        return new DirectoryElement(tag."@name", loadChildren(tag, artifactName));
      case "archive":
        return new ArchiveElement(tag."@name", loadChildren(tag, artifactName));
      case "artifact":
        return new ArtifactLayoutElement(artifactName: tag."@artifact-name")
      case "file-copy":
        def path = macroExpander.expandMacros(tag."@path")
        if (!new File(path).exists()) {
           project.warning("Error in '$artifactName' artifact: file '$path' doesn't exist")
        }
        return new FileCopyElement(filePath: path,
                                   outputFileName: tag."@output-file-name");
      case "dir-copy":
        def path = macroExpander.expandMacros(tag."@path")
        if (!new File(path).exists()) {
          project.warning("Error in '$artifactName' artifact: directory '$path' doesn't exist")
        }
        return new DirectoryCopyElement(dirPath: path);
      case "extracted-dir":
        def jarPath = macroExpander.expandMacros(tag."@path")
        String pathInJar = tag."@path-in-jar"
        if (pathInJar == null) pathInJar = "/"
        if (!new File(pathInJar).exists()) {
          project.warning("Error in '$artifactName' artifact: file '$jarPath' doesn't exist")
        }
        return new ExtractedDirectoryElement(jarPath: jarPath, pathInJar: pathInJar)
      case "module-output":
        def name = tag."@name"
        if (project.modules[name] == null) {
          project.error("Unknown module '$name' in '$artifactName' artifact")
        }
        return new ModuleOutputElement(moduleName: name);
      case "library":
        return new LibraryFilesElement(libraryLevel: tag."@level", libraryName: tag."@name", moduleName: tag."@module-name");
    }

    LayoutElementTypeService type = findType(id)
    if (type != null) {
      return type.createElement(project, tag, macroExpander)
    }

    project.error("unknown element in '$artifactName' artifact: $id");
  }

  private LayoutElementTypeService findType(String typeId) {
    if (elementTypes == null) {
      elementTypes = [:]
      elementTypeLoader.each {LayoutElementTypeService type ->
        elementTypes[type.typeId] = type
      }
    }
    return elementTypes[typeId]
  }

  private ArtifactPropertiesProviderService findPropertiesProvider(String id) {
    if (propertiesProviders == null) {
      propertiesProviders = [:]
      propertiesProvidersLoader.each {ArtifactPropertiesProviderService provider ->
        propertiesProviders[provider.id] = provider
      }
    }
    return propertiesProviders[id]
  }

  Map<String, ArtifactProperties> loadOptions(Node artifactTag, String artifactName) {
    def Map<String, ArtifactProperties> res = [:];
    artifactTag.properties.each {Node propertiesNode ->
      def String id = propertiesNode."@id";
      ArtifactPropertiesProviderService provider = findPropertiesProvider(id)
      if (provider != null) {
        try {
          res[id] = provider.loadProperties(propertiesNode.options[0], macroExpander)
        } catch (Exception e) {
          project.warning("Failed to load properties of the artifact: $artifactName, error: " + e.getMessage());
        }
      }
      else {
        project.debug("Unknown properties '$id' in '$artifactName' artifact")
      }
    }
    return res;
  }

  List<LayoutElement> loadChildren(Node node, String artifactName) {
    node.element.collect { loadLayoutElement(it, artifactName) }
  }
}
