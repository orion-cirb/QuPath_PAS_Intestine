import org.apache.commons.io.FilenameUtils
import static qupath.lib.scripting.QP.*


// Load annotations from file
def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
def imageName = getCurrentImageData().getServer().getMetadata().getName()
def imgNameWithOutExt = FilenameUtils.removeExtension(imageName)

// Delete all annotations
clearAllObjects()

// Find annotations files for current image
def p = ~/${imgNameWithOutExt}.*\.annot/
def resultsDir = new File(buildFilePath(imageDir+'/Results'))
resultsDir.eachFileMatch(p) {file ->
    new File(file.path).withObjectInputStream {
        def annotations = it.readObject()
        print('Adding annotation ' + annotations.toString())
        addObjects(annotations)
    }
}
resolveHierarchy()
