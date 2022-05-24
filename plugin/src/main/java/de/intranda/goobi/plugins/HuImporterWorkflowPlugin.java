package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPushPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.omnifaces.cdi.PushContext;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;

@PluginImplementation
@Log4j2
public class HuImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {
    @Getter
    private ArrayList<LogMessage> errorList;
    private BeanHelper bhelp;
    @Getter
    private String title = "intranda_workflow_hu_importer";
    private long lastPush = System.currentTimeMillis();
    @Getter
    private List<ImportSet> importSets;
    private PushContext pusher;
    private XMLConfiguration config = null;
    private HierarchicalConfiguration mappingNode = null;
    @Getter
    private boolean run = false;
    @Getter
    private int progress = -1;
    @Getter
    private int itemCurrent = 0;
    @Getter
    int itemsTotal = 0;
    @Getter
    private Queue<LogMessage> logQueue = new CircularFifoQueue<LogMessage>(48);
    private Prefs prefs;
    private ArrayList<String> failedImports;

    @Override
    public PluginType getType() {
        return PluginType.Workflow;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_workflow_hu_importer.xhtml";
    }

    /**
     * Constructor
     */
    public HuImporterWorkflowPlugin() {
        log.info("Sample importer workflow plugin started");

        // read important configuration first
        try {
            readConfiguration();
        } catch (NullPointerException ex) {
            String message = "Invalid ImportSet configuration. Mandatory parameter missing! Please correct the configuration file";
            log.error(message, ex);
            updateLog(message, 3);

        }
    }

    /**
     * private method to read main configuration file
     */
    private void readConfiguration() throws NullPointerException {
        updateLog("Start reading the configuration");
        config = ConfigPlugins.getPluginConfig(title);

        // read list of ImportSet configuration
        importSets = new ArrayList<ImportSet>();
        List<HierarchicalConfiguration> mappings = config.configurationsAt("importSet");
        for (HierarchicalConfiguration node : mappings) {
            String name = node.getString("[@name]", null);
            String metadataFolder = node.getString("[@metadataFolder]", null);
            String mediaFolder = node.getString("[@mediaFolder]", null);
            String workflow = node.getString("[@workflow]", null);
            String project = node.getString("[@project]", null);
            String mappingSet = node.getString("[@mappingSet]", null);
            String publicationType = node.getString("[@publicationType]", null);
            String structureType = node.getString("[@structureType]", null);
            int rowStart = node.getInt("[@rowStart]", 2);
            int rowEnd = node.getInt("[@rowEnd]", 0);
            String importSetDescription = node.getString("[@importSetDescription]", null);
            String descriptionMappingSet = node.getString("[@descriptionMappingSet]", null);
            boolean useFileNameAsProcessTitle = node.getBoolean("[@useFileNameAsProcessTitle]", false);
            importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mappingSet, publicationType, structureType, rowStart,
                    rowEnd, useFileNameAsProcessTitle, importSetDescription, descriptionMappingSet));
        }

        // write a log into the UI
        updateLog("Configuration successfully read");
    }

    /**
     * cancel a running import
     */
    public void cancel() {
        run = false;
    }

    /**
     * reads List with MappingFields from the configuration for a given Mapping
     * 
     * @param mappingName
     * @return
     */
    private List<MappingField> getMapping(String mappingName) {
        // find the correct mapping node
        mappingNode = null;
        for (HierarchicalConfiguration node : config.configurationsAt("mappingSet")) {
            String name = node.getString("[@name]");
            if (name.equals(mappingName)) {
                //log.debug("Configured mapping was found: " + name);
                mappingNode = node;
                break;
            }
        }
        // if mapping node was not found, send back error message
        if (mappingNode == null) {
            return null;
        }

        // create a list of all fields to import
        List<MappingField> mappingFields = new ArrayList<MappingField>();
        List<HierarchicalConfiguration> fields = mappingNode.configurationsAt("field");
        try {
            for (HierarchicalConfiguration field : fields) {
                String column = field.getString("[@column]", null);
                String label = field.getString("[@label]", null);
                String mets = field.getString("[@mets]", null);
                String type = field.getString("[@type]", null);
                String separator = field.getString("[@separator]", ",");
                boolean blankBeforeSeparator = field.getBoolean("[@blankBeforeSeparator]", false);
                boolean blankAfterSeparator = field.getBoolean("[@blankAfterSeparator]", false);
                mappingFields.add(new MappingField(column, label, mets, type, separator, blankBeforeSeparator, blankAfterSeparator));
            }
            return mappingFields;
        } catch (NullPointerException ex) {
            String message = "Invalid MappingSet configuration. Mandatory parameter missing! Please correct the configuration file! Import aborted!";
            log.error(message, ex);
            updateLog(message, 3);

        }
        return null;
    }

    /**
     * reads the importset and the xls file with the processdescription if it finds a des
     * 
     * @param importSet
     * @param processFile
     * @return
     */
    private ProcessDescription getProcessDescription(ImportSet importSet, Path processFile) {
        Row processDescriptionRow = null;
        if (importSet.getImportSetDescription() != null) {
            List<MappingField> processMetadata = getMapping(importSet.getDescriptionMappingSet());
            List<MappingField> processDescription = new ArrayList<MappingField>();
            HashMap<String, String> processProperties = new HashMap<String, String>();

            MappingField fileNameColumn = null;
            if (processMetadata == null) {
                updateLog("No valid ImportSetDescription with the Name: " + importSet.getDescriptionMappingSet() + " was found!", 3);
                failedImports.add(processFile.getFileName().toString());
                return null;
            }

            //filter processproperties from processMetadata
            Iterator<MappingField> metaData = processMetadata.iterator();
            while (metaData.hasNext()) {
                MappingField field = metaData.next();
                for (ProcessProperties typeName : ProcessProperties.values()) {
                    if (field.getType().equals(typeName.toString())) {
                        processDescription.add(field);
                        metaData.remove();
                    }
                }
            }

            // get field that maps Column with FileName
            for (MappingField field : processDescription) {
                if (field.getType().equals("FileName")) {
                    fileNameColumn = field;
                    break;
                }
            }

            if (fileNameColumn != null) {
                try {
                    XlsReader reader = new XlsReader(importSet.getImportSetDescription());
                    Sheet sheet = reader.getSheet();

                    for (Row row : sheet) {
                        if (row.getRowNum() == 0) {
                            continue;
                            //skip header
                        }
                        if (XlsReader.getCellContent(row, fileNameColumn).equals(processFile.getFileName().toString())) {
                            processDescriptionRow = row;
                        }
                    }
                    if (processDescriptionRow == null) {
                        updateLog("A File with Processdescriptions was specified but the Filename(" + processFile.getFileName().toString()
                                + "was not found in " + importSet.getDescriptionMappingSet() + "!", 3);
                        updateLog("The Import of File: " + processFile.toString() + " will be scipped!", 3);
                        failedImports.add(processFile.getFileName().toString());
                        return null;
                    }

                    for (MappingField field : processDescription) {
                        processProperties.put(field.getType(), XlsReader.getCellContent(processDescriptionRow, field));
                    }

                    //
                    return new ProcessDescription(processDescriptionRow, processMetadata, processProperties, processFile.getFileName());
                } catch (IOException e) {
                    updateLog("Could open File with Path" + importSet.getImportSetDescription(), 3);
                }
            } else {
                updateLog("A File with Processdescriptions was specified but no DescriptionMappingSet was provided!", 3);
                updateLog("The Import of File: " + processFile.toString() + " will be scipped!", 3);
                failedImports.add(processFile.getFileName().toString());
                return null;
            }
        }
        return new ProcessDescription(null, null, null, processFile.getFileName());

    }

    /**
     * main method to start the actual import
     * 
     * @param importConfiguration
     */
    public void startImport(ImportSet importSet) {
        errorList = new ArrayList<LogMessage>();
        failedImports = new ArrayList<String>();
        StorageProviderInterface storageProvider = StorageProvider.getInstance();
        updateLog("Start import for: " + importSet.getName());
        progress = 0;
        bhelp = new BeanHelper();

        //read mappings

        List<MappingField> mappingFields = getMapping(importSet.getMapping());
        if (mappingFields == null || mappingFields.size() == 0) {
            updateLog("Import could not be executed because no MappingSet with the name " + importSet.getMapping() + "  was found!", 3);
            return;
        }

        // create folder for processed files
        Path processedFolder = Paths.get(importSet.getMetadataFolder(), "processed");
        if (!storageProvider.isFileExists(processedFolder)) {
            try {
                storageProvider.createDirectories(processedFolder);
            } catch (IOException e) {
                updateLog("Error creating Folder for processd xls documents! Export aborted " + e.getMessage(), 3);
                return;
            }
        }

        // run the import in a separate thread to allow a dynamic progress bar
        run = true;
        Runnable runnable = () -> {

            // create list with files in metadata folder of importSet
            List<Path> FilesToRead = storageProvider.listFiles(importSet.getMetadataFolder(), HuImporterWorkflowPlugin::isRegularAndNotHidden);
            updateLog("Run through all import files");
            itemsTotal = FilesToRead.size();
            itemCurrent = 0;
            Process process = null;
            try {
                boolean successful = true;
                if (FilesToRead.size() == 0) {
                    updateLog("There are no files int the folder: " + importSet.getMetadataFolder(), 3);
                    successful = false;
                }
                for (Path processFile : FilesToRead) {
                    ProcessDescription processDescription = getProcessDescription(importSet, processFile);
                    if (processDescription == null && !StringUtils.isBlank(importSet.getImportSetDescription())) {
                        updateLog("A importSetDescription was configured but there were Errors getting the Description", 3);
                        successful = false;
                        continue;
                    }

                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }
                    updateLog("Datei: " + processFile.toString());
                    /*
                    FileInputStream inputStream = new FileInputStream(new File(processFile.toString()));
                    Workbook workbook = new XSSFWorkbook(inputStream);
                    Sheet sheet = workbook.getSheetAt(0);
                    */

                    XlsReader reader = new XlsReader(processFile.toString());
                    Sheet sheet = reader.getSheet();

                    try {

                        Set<Path> imageFiles = null;
                        if (importSet.getMediaFolder() != null) {
                            //TODO catch IOException here!
                            imageFiles = filterImagesInFolder(importSet.getMediaFolder());

                        } else {
                            // check if this is desired behavior!
                            updateLog("No mediaFolder specified! Import aborted!", 3);
                            failedImports.add(processFile.getFileName().toString());
                            continue;
                        }

                        // create Process
                        DocumentManager dManager = new DocumentManager(processDescription, importSet, this);
                        process = dManager.getProcess();
                        this.prefs = dManager.getPrefs();
                        updateLog("Start importing: " + process.getTitel(), 1);

                        //read fileformat etc. from process
                        Fileformat fileformat = process.readMetadataFile();
                        DigitalDocument dd = fileformat.getDigitalDocument();
                        DocStruct logical = dd.getLogicalDocStruct();
                        DocStruct physical = dd.getPhysicalDocStruct();

                        if (processDescription != null && processDescription.getMetaDataMapping() != null) {
                            for (MappingField mapping : processDescription.getMetaDataMapping()) {
                                try {
                                    String cellContent = XlsReader.getCellContent(processDescription.getRow(), mapping);
                                    DocStruct ds = addMetadata(prefs, logical, mapping, importSet, cellContent, process);
                                    if (ds != null) {
                                        logical = ds;
                                    } else {
                                        updateLog("Error adding Metadata to Topelement! ", 3);
                                    }
                                } catch (MetadataTypeNotAllowedException e) {
                                    successful = false;
                                    updateLog("Invalid Mapping for Field " + mapping.getType() + " with Mets " + mapping.getMets() + " in MappingSet "
                                            + importSet.getDescriptionMappingSet(), 3);
                                }
                            }
                        }

                        //add imagepath:
                        Metadata imagePath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
                        imagePath.setValue(process.getImagesDirectory());
                        physical.addMetadata(imagePath);

                        //Initialize PageCount
                        int PageCount = 0;
                        for (Row row : sheet) {
                            //skip rows until start row
                            if (row.getRowNum() < importSet.getRowStart() - 1)
                                continue;
                            //end parsing after end row number
                            if (importSet.getRowEnd() != 0 && row.getRowNum() > importSet.getRowEnd())
                                break;
                            //create new Docstruct of 
                            DocStruct ds = dd.createDocStruct(prefs.getDocStrctTypeByName(importSet.getStructureType()));

                            // create the metadata fields by reading the config (and get content from the content files of course)
                            for (MappingField mappingField : mappingFields) {

                                String cellContent = XlsReader.getCellContent(row, mappingField);

                                if (StringUtils.isNotBlank(mappingField.getType()) && StringUtils.isNotBlank(cellContent)) {
                                    if (mappingField.getType().trim().equals("media")) {
                                        String[] imageFileNames = cellContent.split(",");
                                        for (String imageFileName : imageFileNames) {
                                            Path imageFile = imageFiles.stream()
                                                    .filter(path -> path.getFileName().toString().equals(imageFileName.trim()))
                                                    .findFirst()
                                                    .orElse(null);
                                            if (imageFile == null) {
                                                updateLogAndProcess(process.getId(),
                                                        "Couldn't import the following file: " + importSet.getMediaFolder() + imageFileName, 3);
                                                successful = false;
                                            } else {
                                                Path masterFolder = Paths.get(process.getImagesOrigDirectory(false));
                                                if (!storageProvider.isFileExists(masterFolder))
                                                    storageProvider.createDirectories(masterFolder);
                                                if (Files.isReadable(imageFile)) {
                                                    storageProvider.copyFile(imageFile,
                                                            Paths.get(masterFolder.toString(), imageFile.getFileName().toString()));
                                                    if (!addPage(physical, ds, dd, imageFile.toFile(), process.getId(), ++PageCount)) {
                                                        updateLogAndProcess(process.getId(), "Couldn't add Page to Structure", 3);
                                                        successful = false;
                                                    }

                                                } else {
                                                    updateLogAndProcess(process.getId(),
                                                            "Couldn't read the following file: " + importSet.getMediaFolder() + imageFileName, 3);
                                                    successful = false;
                                                }
                                            }
                                        }
                                    } else {

                                        try {
                                            ds = addMetadata(prefs, ds, mappingField, importSet, cellContent, process);
                                        } catch (MetadataTypeNotAllowedException e) {
                                            successful = false;
                                            updateLog("Invalid Mapping for Field " + mappingField.getType() + " in MappingSet "
                                                    + importSet.getMapping(), 3);
                                        }
                                    }
                                }
                                if (ds != null) {
                                    logical.addChild(ds);
                                } else {
                                    successful = false;
                                    updateLogAndProcess(process.getId(), "Error updating Process with metadatatype" + mappingField.getMets(),
                                            PageCount);
                                }
                            }
                        }
                        // write the metsfile
                        process.writeMetadataFile(fileformat);

                        if (successful) {
                            // start any open automatic tasks for the created process
                            for (Step s : process.getSchritteList()) {
                                if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                                    ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                                    myThread.startOrPutToQueue();
                                }
                            }
                            //move parsed xls to processed folder
                            //TODO uncomment
                            //storageProvider.move(processFile, Paths.get(processedFolder.toString(), processFile.getFileName().toString()));
                            updateLog("Process successfully created with ID: " + process.getId(), 2);
                        } else {
                            updateLogAndProcess(process.getId(), "Process automatically created by " + getTitle() + " with ID:" + process.getId(), 1);
                            for (Step s : process.getSchritteList()) {
                                if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN)) {
                                    s.setBearbeitungsstatusEnum(StepStatus.ERROR);
                                    break;
                                }
                            }
                            failedImports.add(processFile.getFileName().toString());
                        }
                        ProcessManager.saveProcess(process);

                    } catch (ProcessCreationException e) {
                        //Shouldn't we end the import here??
                        log.error("Error creating a process during the import", e);
                        updateLog("Error creating a process during the import: " + e.getMessage(), 3);
                    } catch (Exception e) {

                        String message = (process != null) ? "Error mapping and importing data during the import of process: "
                                : "Error creating a process during import";
                        message = message + process.getTitel() + e.getMessage();

                        log.error("Error  during the import for process", e);
                        if (process != null) {
                            updateLogAndProcess(process.getId(), message, 3);
                            try {
                                ProcessManager.saveProcess(process);
                            } catch (DAOException e1) {

                                e1.printStackTrace();
                            }
                        }
                    }

                    // recalculate progress
                    itemCurrent++;
                    progress = 100 * itemCurrent / itemsTotal;
                    updateLog("Processing of record done.");
                }

                // finally last push
                run = false;
                Thread.sleep(1000);
                updateLog("Import completed.", 2);
                if (failedImports.size() > 0) {
                    updateLog("We encountered errors during the import. Please check the logfile and the process logs!", 3);
                    updateLog(failedImports.size() + " Import(s) finished with errors!", 3);
                    failedImports.forEach((importFile) -> {
                        updateLog(importFile, 3);
                    });
                }

            } catch (InterruptedException | IOException e) {
                Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                log.error("Error trying to execute the import", e);
                updateLog("Error trying to execute the import: " + e.getMessage(), 3);
            }

            pusher.send("summary");
        };
        new Thread(runnable).start();

    }

    /**
     * checks if the path isn't a directory and if it's not hidden
     * 
     * @param path
     * @return
     */
    public static boolean isRegularAndNotHidden(Path path) {

        try {
            return !Files.isDirectory(path) && !Files.isHidden(path);
        } catch (IOException e) {
            //if we can't open it we will not add it to the List
            return false;
        }

    }

    /**
     * returns list with paths of images in the provided folder
     * 
     * @param mediaFolder
     * @return
     * @throws IOException
     */
    private Set<Path> filterImagesInFolder(String mediaFolder) throws IOException {
        try (Stream<Path> stream = Files.list(Paths.get(mediaFolder))) {
            return stream.filter(file -> {
                return !Files.isDirectory(file) && NIOFileUtils.checkImageType(file.getFileName().toString());
            }).collect(Collectors.toSet());
        }

    }

    /**
     * Adds metadata to the DocStruct Element
     * 
     * @param prefs
     * @param ds
     * @param mappingField
     * @param importSet
     * @param cellContent
     * @param process
     * @return
     * @throws MetadataTypeNotAllowedException
     */
    private DocStruct addMetadata(Prefs prefs, DocStruct ds, MappingField mappingField, ImportSet importSet, String cellContent, Process process)
            throws MetadataTypeNotAllowedException {
        switch (mappingField.getType()) {
            case "person":
                if (mappingField.getMets() == null) {
                    updateLogAndProcess(process.getId(), "No Mets provided. Please update the Mapping " + importSet.getMapping(), 3);
                    return null;
                }
                updateLog("Add person '" + mappingField.getMets() + "' with value '" + cellContent + "'");
                Person p = new Person(prefs.getMetadataTypeByName(mappingField.getMets()));
                String firstname = cellContent.substring(0, cellContent.indexOf(" "));
                String lastname = cellContent.substring(cellContent.indexOf(" "));
                p.setFirstname(firstname);
                p.setLastname(lastname);
                ds.addPerson(p);
                break;
            case "metadata":
                if (mappingField.getMets() == null) {
                    updateLogAndProcess(process.getId(), "No Mets provided. Please update the Mapping " + importSet.getMapping(), 3);
                    return null;
                }
                Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                md.setValue(cellContent);
                ds.addMetadata(md);
                break;
            case "FileName":
                //do nothhing
                return ds;
            default:
                updateLogAndProcess(process.getId(), "the specified type: " + mappingField.getType() + " is not supported", 3);
                return null;
        }
        return ds;
    }

    /**
     * adds page to the physical docstruct and links it to the logical docstruct-element
     * 
     * @param physicaldocstruct
     * @param logical
     * @param dd
     * @param imageFile
     * @param processId
     * @param pageNo
     * @return
     * @throws TypeNotAllowedForParentException
     * @throws IOException
     * @throws InterruptedException
     * @throws SwapException
     * @throws DAOException
     */
    private boolean addPage(DocStruct physicaldocstruct, DocStruct logical, DigitalDocument dd, File imageFile, int processId, int pageNo)
            throws TypeNotAllowedForParentException, IOException, InterruptedException, SwapException, DAOException {

        DocStructType newPage = prefs.getDocStrctTypeByName("page");

        DocStruct dsPage = dd.createDocStruct(newPage);
        try {
            // physical page no
            physicaldocstruct.addChild(dsPage);
            MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
            Metadata mdTemp = new Metadata(mdt);
            mdTemp.setValue(String.valueOf(pageNo));
            dsPage.addMetadata(mdTemp);

            // logical page no
            mdt = prefs.getMetadataTypeByName("logicalPageNumber");
            mdTemp = new Metadata(mdt);

            mdTemp.setValue("uncounted");

            dsPage.addMetadata(mdTemp);
            logical.addReferenceTo(dsPage, "logical_physical");

            // image name
            ContentFile cf = new ContentFile();

            cf.setLocation("file://" + imageFile.getName());

            dsPage.addContentFile(cf);
            if (pageNo % 10 == 0) {
                updateLog("Created " + pageNo + "physical Pages for Process with Id: " + processId);
            }

            return true;
        } catch (TypeNotAllowedAsChildException e) {
            updateLogAndProcess(1, "Error creating page - type not allowed as child", 3);
            log.error("Error creating Page", e);
            return false;
        } catch (MetadataTypeNotAllowedException e) {
            log.error("Error creating Page", e);
            updateLogAndProcess(1, "Error creating page - Metadata type not allowed", 3);
            return false;
        }
    }

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;
    }

    /**
     * simple method to send status message to gui
     * 
     * @param logmessage
     */
    private void updateLog(String logmessage) {
        updateLog(logmessage, 0);
    }

    private void updateLogAndProcess(int processId, String message, int level) {
        LogType type = (level == 3) ? LogType.ERROR : (level == 1) ? LogType.DEBUG : LogType.INFO;

        Helper.addMessageToProcessLog(processId, type, message);
        updateLog(message, level);
    }

    /**
     * simple method to send status message with specific level to gui
     * 
     * @param logmessage
     */
    public void updateLog(String logmessage, int level) {
        LogMessage message = new LogMessage(logmessage, level);
        logQueue.add(message);
        if (level == 3)
            errorList.add(message);
        log.debug(logmessage);
        if (pusher != null && System.currentTimeMillis() - lastPush > 500) {
            lastPush = System.currentTimeMillis();
            pusher.send("update");
        }
    }

    @Data
    @AllArgsConstructor
    public class MappingField {
        @NonNull
        private String column;
        private String label;
        private String mets;
        @NonNull
        private String type;
        @NonNull
        private String separator;
        private boolean blankBeforeSeparator;
        private boolean blankAfterSeparator;
    }

    @Data
    @AllArgsConstructor
    public class ImportSet {
        @NonNull
        private String name;
        @NonNull
        private String metadataFolder;
        private String mediaFolder;
        @NonNull
        private String workflow;
        private String project;
        @NonNull
        private String mapping;
        @NonNull
        private String publicationType;
        @NonNull
        private String structureType;
        private int rowStart;
        private int rowEnd;
        private boolean useFileNameAsProcessTitle;
        private String importSetDescription;
        private String DescriptionMappingSet;
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }

    @Data
    @AllArgsConstructor
    public class ProcessDescription {
        private Row row;
        private List<MappingField> metaDataMapping;
        private HashMap<String, String> ProcessProperties;
        private Path fileName;
    }
}
