import org.apache.commons.io.FilenameUtils
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.objects.classes.PathClassFactory
import static qupath.lib.gui.scripting.QPEx.*
import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs


// init project

def project = getProject()
def pathProject = buildFilePath(PROJECT_BASE_DIR)
def pathModel = buildFilePath(pathProject,'models','dsb2018_heavy_augment.pb')
if (pathModel == null)
    print('No model found')

def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
def dnn = DnnTools.builder(pathModel).build()
// create results file and write headers
def resultsDir = buildFilePath(imageDir, 'Results')
if (!fileExists(resultsDir)) {
    mkdirs(resultsDir)
}
def resultsFile = new File(buildFilePath(resultsDir, 'Results.csv'))
resultsFile.createNewFile()
def resHeaders = 'Image Name\tAnnotation Name\tArea\tnb Cells\tCell density\tCells Mean intensity\n'
resultsFile.write(resHeaders)

// Classpath definition
def cellsClass = PathClassFactory.getPathClass('Cells', makeRGB(0,0,255))

def stardistCells = StarDist2D.builder(pathModel)
          .threshold(0.60)         // Prediction threshold
          .normalizePercentiles(1, 99)     // Percentile normalization
          .pixelSize(0.25)             // Resolution for detection
          .channels(ColorTransforms.createColorDeconvolvedChannel(getCurrentImageData().getColorDeconvolutionStains(), 2))
          .preprocess(
                  ImageOps.Filters.gaussianBlur(4)
          )
          .doLog()
          .tileSize(1024)
          .measureShape()
          .measureIntensity()
          .build()


// Save annotations
def saveAnnotations(imgName) {
    def path = buildFilePath(imgName + '.annot')
    def annotations = getAnnotationObjects().findAll {it.hasChildren()}
    new File(path).withObjectOutputStream {
        it.writeObject(annotations)
    }
    println('Annotations saved...')
}

// Get objects intensity
def getObjectsIntensity(cells, channel) {
    def measure = channel + ': Mean'
    def means = 0
    def nbCells = cells.size()
    if (nbCells) {
        for (cell in cells) {
            means += cell.getMeasurementList().getMeasurementValue(measure)
        }
        means = means / cells.size()
    }
    return means
}

// loop over images in project

for (entry in project.getImageList()) {
    def imageData = entry.readImageData()
    def server = imageData.getServer()
    def cal = server.getPixelCalibration()
    def pixelWidth = cal.getPixelWidth().doubleValue()
    def pixelUnit = cal.getPixelWidthUnit()
    def imgName = entry.getImageName()
    def imgNameWithOutExt = FilenameUtils.removeExtension(imgName)


    setBatchProjectAndImage(project, imageData)
    clearAnnotations()
    clearAllObjects()
    println('-Finding intestine region ...')
    def classifier = project.getPixelClassifiers().get('Intestine')
    createAnnotationsFromPixelClassifier(classifier, 1000.0, 0.0, 'DELETE_EXISTING')
    // find annotations
    def hierarchy = imageData.getHierarchy()

    def intestineRegion = getAnnotationObjects()
    if (intestineRegion.isEmpty()) {
        Dialogs.showErrorMessage("Problem", "No ROIs detected")
        return
    }
    intestineRegion[0].setName(imgNameWithOutExt+"_Intestine")
    // get annotation area
    def regionArea = intestineRegion[0].getROI().getScaledArea(pixelWidth, pixelWidth)
    println intestineRegion[0].getName() + ' area = ' + regionArea + ' ' + pixelUnit
    selectAnnotations()

    // Do cells detections
    // Set color deconvolution for HE & PAS
    setImageType('Brightfield (other)')
    setColorDeconvolutionStains('{"Name" : "He-PAS", "Stain 1" : "Hematoxylin", "Values 1" : "0.6446 0.71666 0.26625", "Stain 2" : "PAS", "Values 2" : "0.17508 0.97243 0.15407", "Background" : " 253 253 253"}');

    stardistCells.detectObjects(imageData, intestineRegion[0], true)
    dnn.getPredictionFunction().net.close()
    def cells = getDetectionObjects().findAll{it.getMeasurementList().getMeasurementValue('Area µm^2') > 30 && it.getMeasurementList().getMeasurementValue('PAS: Mean') > 0.7}
    cells.each{it.setPathClass(cellsClass)}
    println 'Nb cells in region = ' + cells.size()
    // Find cells means intensities
    def cellsMeanInt = getObjectsIntensity(cells, 'PAS')
    println 'Mean cells intensity in region = ' + cellsMeanInt

    // Results
    def results = imgNameWithOutExt + '\t' + intestineRegion[0].getName() + '\t' + regionArea + '\t' + cells.size() + '\t' + cells.size()/regionArea+ '\t'+cellsMeanInt  + '\n'
    resultsFile << results

    // add detections and save detections
    clearDetections()
    addObjects(cells)
    addObjects(intestineRegion)
    resolveHierarchy()
    // Save annotations in Shapes format
    saveAnnotations(buildFilePath(resultsDir, intestineRegion[0].getName()))

    }

