import org.apache.commons.io.FilenameUtils
import qupath.lib.color.ColorDeconvolutionStains
import qupath.lib.color.StainVector
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.objects.classes.PathClassFactory
import qupath.opencv.ops.ImageOps
import static qupath.lib.gui.scripting.QPEx.*
import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

// init project

def project = getProject()
def pathProject = buildFilePath(PROJECT_BASE_DIR)
def pathModel = buildFilePath(pathProject,'models','dsb2018_heavy_augment.pb')
if (pathModel == null)
    print('No model found')

def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
// create results file and write headers
def resultsDir = buildFilePath(imageDir, 'Results')
if (!fileExists(resultsDir)) {
    mkdirs(resultsDir)
}
def resultsFile = new File(buildFilePath(resultsDir, 'Results.csv'))
resultsFile.createNewFile()
def resHeaders = 'Image Name\tAnnotation Name\tArea\tnb Cells\tCell density\tCells Mean intensity\tCells Std intensity\tCells Median intensity\t' +
        'Cells Sum area\tCells Mean area\tCells Std area\tCells Median area\n'
resultsFile.write(resHeaders)

// Classpath definition
def cellsClass = PathClassFactory.getPathClass('Cells', makeRGB(0,0,255))

// Set color deconvolution for HE & PAS
def stain1 = new StainVector().createStainVector("Hematoxylin",0.656, 0.695, 0.296)
def stain2 = new StainVector().createStainVector("PAS",0.183, 0.956, 0.23)
def colorStainsHe_PAS = new ColorDeconvolutionStains('He-PAS',stain1, stain2, 253, 253, 254)

def stardistCells = StarDist2D.builder(pathModel)
          .threshold(0.50)         // Prediction threshold
          .normalizePercentiles(1, 99)     // Percentile normalization
          .pixelSize(0.25)             // Resolution for detection
          .channels(ColorTransforms.createColorDeconvolvedChannel(colorStainsHe_PAS, 2))
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

// Get objects parameters (mean, std, median)
def getObjectsParameters(cells, param) {
    def params = new DescriptiveStatistics();
    def nbCells = cells.size()
    if (nbCells) {
        for (cell in cells) {
            params.addValue(cell.getMeasurementList().getMeasurementValue(param))
        }
    }
    def paramsValues = [params.sum, params.mean, params.standardDeviation, params.getPercentile(50)]
    return paramsValues
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

    // set color deconvolution to He&PAS
    imageData.setColorDeconvolutionStains(colorStainsHe_PAS)


    println('-Finding intestine region ...')
    def classifier = project.getPixelClassifiers().get('Intestine')
    createAnnotationsFromPixelClassifier(classifier, 300000.0, 0.0, "SPLIT", 'DELETE_EXISTING')
    // find annotations
    def hierarchy = imageData.getHierarchy()

    def intestineRegion = getAnnotationObjects()
    if (intestineRegion.isEmpty()) {
        Dialogs.showErrorMessage("Problem", "No ROIs detected")
        return
    }
    // loop over sections
    def index = 0
    for (s in intestineRegion) {
        s.setName(imgNameWithOutExt + "_Intestine_" + index)
        index++
        // get annotation area
        def regionArea = s.getROI().getScaledArea(pixelWidth, pixelWidth)
        println s.getName() + ' area = ' + regionArea + ' ' + pixelUnit
       // selectObjects(s)

        // Do cells detections
        stardistCells.detectObjects(imageData, s, true)
        def cells = getDetectionObjects().findAll { it.getMeasurementList().getMeasurementValue('Area µm^2') > 30 && it.getMeasurementList().getMeasurementValue('PAS: Mean') > 0.7 }
        cells.each { it.setPathClass(cellsClass) }
        println 'Nb cells in region = ' + cells.size()

        // Find cells means intensities
        def cellsMeanInt = getObjectsParameters(cells, 'PAS: Mean')[1]
        def cellsStdInt = getObjectsParameters(cells, 'PAS: Mean')[2]
        def cellsMedianInt = getObjectsParameters(cells, 'PAS: Mean')[3]
        println 'Mean cells intensity in region = ' + cellsMeanInt

        // Find cells means intensities
        def cellsSumArea = getObjectsParameters(cells, 'Area µm^2')[0]
        def cellsMeanArea = getObjectsParameters(cells, 'Area µm^2')[1]
        def cellsStdArea = getObjectsParameters(cells, 'Area µm^2')[2]
        def cellsMedianArea = getObjectsParameters(cells, 'Area µm^2')[3]
        println 'Mean cells intensity in region = ' + cellsMeanArea

        // Results
        def results = imgNameWithOutExt + '\t' + s.getName() + '\t' + regionArea + '\t' + cells.size() + '\t' + cells.size() / regionArea + '\t' + cellsMeanInt + '\t' + cellsStdInt + '\t' + cellsMedianInt+ '\t' + cellsSumArea + '\t' + cellsMeanArea + '\t' + cellsStdArea + '\t' + cellsMedianArea + '\n'
        resultsFile << results

        // add detections and save detections
        clearDetections()
        addObjects(cells)
        addObjects(intestineRegion)
        resolveHierarchy()
        // Save annotations in Shapes format
        saveAnnotations(buildFilePath(resultsDir, s.getName()))
    }
}

