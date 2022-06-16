package de.intranda.goobi.plugins;

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
import ugh.dl.DocStruct;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class HuImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {
    @Getter
    private ArrayList<LogMessage> errorList = new ArrayList<LogMessage>();
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
    private boolean successful = true;

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

        //read important configuration first
        readConfiguration();
    }

    /**
     * private method to read main configuration file
     */
    private void readConfiguration() {
        updateLog("Start reading the configuration");
        config = ConfigPlugins.getPluginConfig(title);

        // read list of ImportSet configuration
        importSets = new ArrayList<ImportSet>();
        try {
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
                String eadType = node.getString("[@eadType]", null);
                String eadFile = node.getString("[@eadFile]", null);
                String eadNode = node.getString("[@eadNode]", null);
                String eadSubnodeType = node.getString("[@eadSubnodeType]",null);
                importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mappingSet, publicationType, structureType,
                        rowStart, rowEnd, useFileNameAsProcessTitle, importSetDescription, descriptionMappingSet, eadType, eadFile, eadNode, eadSubnodeType));
            }

            // write a log into the UI
            updateLog("Configuration successfully read");
        } catch (NullPointerException ex) {
            String logmessage= "Invalid ImportSet configuration. Mandatory parameter missing! Please correct the configuration file";
            importSets = new ArrayList<ImportSet>();
            log.error(logmessage,ex);
            LogMessage message = new LogMessage(logmessage, 3);
            logQueue.add(message);
            errorList.add(message);
            successful = false;
            
            log.debug(logmessage);
            if (pusher != null && System.currentTimeMillis() - lastPush > 500) {
                lastPush = System.currentTimeMillis();
                pusher.send("update");
            }
        }
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
                // log.debug("Configured mapping was found: " + name);
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
                String ead = field.getString("[@ead]", null);
                mappingFields.add(new MappingField(column, label, mets, type, separator, blankBeforeSeparator, blankAfterSeparator, ead));
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
     * reads the importset and the xls file with the processdescription
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

            // filter processproperties from processMetadata
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
                            // skip header
                        }
                        if (XlsReader.getCellContent(row, fileNameColumn).equals(processFile.getFileName().toString())) {
                            processDescriptionRow = row;
                        }
                    }
                    if (processDescriptionRow == null) {
                        updateLog("A File with Processdescriptions was specified but the Filename (" + processFile.getFileName().toString()
                                + ") was not found in " + importSet.getDescriptionMappingSet() + "!", 3);
                        updateLog("The Import of File: " + processFile.toString() + " will be scipped!", 3);
                        failedImports.add(processFile.getFileName().toString());
                        reader.closeWorkbook();
                        return null;
                    }

                    for (MappingField field : processDescription) {
                        processProperties.put(field.getType(), XlsReader.getCellContent(processDescriptionRow, field));
                    }

                    reader.closeWorkbook();
                    return new ProcessDescription(processDescriptionRow, processMetadata, processProperties, processFile.getFileName());

                } catch (IOException e) {
                    updateLog("Could not open File with Path: " + importSet.getImportSetDescription(), 3);
                    updateLog("The Import of File: " + processFile.toString() + " will be scipped!", 3);
                    return null;
                }
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

        // read mappings

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
        Path failureFolder = Paths.get(importSet.getMetadataFolder(), "failure");
        if (!storageProvider.isFileExists(failureFolder)) {
            try {
                storageProvider.createDirectories(failureFolder);
            } catch (IOException e) {
                updateLog("Error creating Folder for xls documents of failed Imports! Export aborted " + e.getMessage(), 3);
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

                if (FilesToRead.size() == 0) {
                    updateLog("There are no files in the folder: " + importSet.getMetadataFolder(), 3);
                }
                for (Path processFile : FilesToRead) {
                    successful = true;
                    ProcessDescription processDescription = getProcessDescription(importSet, processFile);
                    if (processDescription == null && !StringUtils.isBlank(importSet.getImportSetDescription())) {
                        updateLog("A importSetDescription was configured but there were Errors getting the Description", 3);
                        continue;
                    }

                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }
                    updateLog("Datei: " + processFile.toString());

                    //Try to open File if IOException flies here, no process will be created
                    XlsReader reader = new XlsReader(processFile.toString());
                    Sheet sheet = reader.getSheet();

                    try {
                        Set<Path> imageFiles = null;
                        if (importSet.getMediaFolder() != null) {
                            // TODO catch IOException here!
                            imageFiles = filterImagesInFolder(importSet.getMediaFolder());
                        } else {
                            // check if this is desired behavior!
                            updateLog("No mediaFolder specified! Import aborted!", 3);
                            failedImports.add(processFile.getFileName().toString());
                            continue;
                        }
                        // create Process, DocumentManager and EadManager
                        DocumentManager dManager = new DocumentManager(processDescription, importSet, this);
                        process = dManager.getProcess();
                        EadManager eadManager = null;
                        String nodeId=null;
                        if (StringUtils.isNotBlank(importSet.getEadFile())) {
                            eadManager = new EadManager(importSet, process.getTitel());
                            if (eadManager.isDbStatusOk()) {
                                nodeId = eadManager.addDocumentNodeWithMetadata(processDescription.getRow(), processDescription.getMetaDataMapping());
                            } else {
                                updateLogAndProcess(process.getId(), "Couldn't open baseX-DB, no EAD-Entries were generated for this process", 3);
                            }
                        }
                        this.prefs = dManager.getPrefs();
                        updateLog("Start importing: " + process.getTitel(), 1);

                        if (processDescription != null && processDescription.getMetaDataMapping() != null) {
                            for (MappingField mapping : processDescription.getMetaDataMapping()) {
                                try {
                                    String cellContent = XlsReader.getCellContent(processDescription.getRow(), mapping);
                                    dManager.addMetaDataToTopStruct(mapping, cellContent);
                                } catch (MetadataTypeNotAllowedException e) {
                                    updateLog("Invalid Mapping for Field " + mapping.getType() + " with Mets " + mapping.getMets() + " in MappingSet "
                                            + importSet.getDescriptionMappingSet(), 3);
                                }
                            }
                            try {
								dManager.addNodeIdToTopStruct(nodeId);
							} catch (MetadataTypeNotAllowedException e) {
                                updateLog("Metadata field definition for nodeId is missing (needed to link document with ead-nodes)! Please update the ruleset.", 3);
							}
                        }
                        // Initialize PageCount
                        int PageCount = 0;
                        for (Row row : sheet) {
                            // skip rows until start row
                            if (row.getRowNum() < importSet.getRowStart() - 1)
                                continue;
                            String subnodeId =null;
                            //only add ead subnodes if a SubnodeType is specified
                            if (StringUtils.isNotBlank(importSet.getEadSubnodeType())&& eadManager.isDbStatusOk()) {
                            	subnodeId = eadManager.addSubnodeWithMetaData(row,mappingFields);
                            }
                            // end parsing after end row number
                            if (importSet.getRowEnd() != 0 && row.getRowNum() > importSet.getRowEnd())
                                break;

                            // create the metadata fields by reading the config (and get content from the
                            // content files of course)
                            dManager.createStructureWithMetaData(row, mappingFields, imageFiles, subnodeId);
                            

                        }
                        // close workbook
                        reader.closeWorkbook();
                        // write the metsfile
                        dManager.writeMetadataFile();
                        updateLogAndProcess(process.getId(), "Process automatically created by " + getTitle() + " with ID:" + process.getId(), 1);
                        if (successful) {
                            // start any open automatic tasks for the created process
                            for (Step s : process.getSchritteList()) {
                                if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                                    ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                                    myThread.startOrPutToQueue();
                                }
                            }

                            // move parsed xls to processed folder
                            storageProvider.move(processFile, Paths.get(processedFolder.toString(), processFile.getFileName().toString()));

                        } else {
                            // move parsed xls to failure folder
                            storageProvider.move(processFile, Paths.get(failureFolder.toString(), processFile.getFileName().toString()));

                            for (Step s : process.getSchritteList()) {
                                if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN)) {
                                    s.setBearbeitungsstatusEnum(StepStatus.ERROR);
                                    break;
                                }
                            }
                            failedImports.add(processFile.getFileName().toString());
                        }

                        //if eadManager was used save Changes
                        if (eadManager != null && eadManager.isDbStatusOk()) {
                            eadManager.saveArchiveAndLeave();
                        }
                        dManager.saveProcess();

                    } catch (ProcessCreationException e) {
                        // Shouldn't we end the import here??
                        log.error("Error creating a process during the import", e);
                        updateLog("Error creating a process during the import: " + e.getMessage(), 3);
                    } catch (IOException | TypeNotAllowedAsChildException | TypeNotAllowedForParentException | PreferencesException | SwapException
                            | WriteException | DAOException e) {

                        String message = (process != null) ? "Error mapping and importing data during the import of process: "
                                : "Error creating a process during import";
                        message = message + process.getTitel() + " " + e.getMessage();

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
            // if we can't open it we will not add it to the List
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

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;
    }

    /**
     * simple method to send status message to gui
     * 
     * @param logmessage
     */
    public void updateLog(String logmessage) {
        updateLog(logmessage, 0);
    }

    public void updateLogAndProcess(int processId, String message, int level) {
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
        if (level == 3) {
            errorList.add(message);
            successful = false;
        }
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
        private String separator;
        private boolean blankBeforeSeparator;
        private boolean blankAfterSeparator;
        private String ead;
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
        private String eadType;
        private String eadFile;
        private String eadNode;
        private String eadSubnodeType;
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
