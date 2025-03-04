package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class HuImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {

    private static final long serialVersionUID = -8921468910160725693L;
    @Getter
    private transient List<LogMessage> errorList = new ArrayList<>();
    @Getter
    private String title = "intranda_workflow_hu_importer";
    private long lastPush = System.currentTimeMillis();
    @Getter
    private transient List<ImportSet> importSets;
    private PushContext pusher;
    private XMLConfiguration config = null;
    @Getter
    private boolean run = false;
    @Getter
    private int progress = -1;
    @Getter
    private int itemCurrent = 0;
    @Getter
    int itemsTotal = 0;
    @Getter
    private transient Queue<LogMessage> logQueue = new CircularFifoQueue<>(48);

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
        this.config = ConfigPlugins.getPluginConfig(this.title);

        // read list of ImportSet configuration
        this.importSets = new ArrayList<>();
        try {
            List<HierarchicalConfiguration> mappings = this.config.configurationsAt("importSet");
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
                String processTitleMode = node.getString("[@processTitleMode]", "UUID");
                String eadType = node.getString("[@eadType]", null);
                String eadFile = node.getString("[@eadFile]", null);
                String eadNode = node.getString("[@eadNode]", null);
                String eadSubnodeType = node.getString("[@eadSubnodeType]", null);
                boolean processPerRow = node.getBoolean("[@processPerRow]", false);
                this.importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mappingSet, publicationType, structureType,
                        rowStart, rowEnd, processTitleMode, importSetDescription, descriptionMappingSet, eadType, eadFile, eadNode, eadSubnodeType,
                        processPerRow));
            }

            // write a log into the UI
            updateLog("Configuration successfully read");
        } catch (NullPointerException ex) {
            String logmessage = "Invalid ImportSet configuration. Mandatory parameter missing! Please correct the configuration file.";
            this.importSets = new ArrayList<>();
            log.error(logmessage, ex);
            LogMessage message = new LogMessage(logmessage, 3);
            this.logQueue.add(message);
            this.errorList.add(message);
            this.successful = false;

            log.debug(logmessage);
            if (this.pusher != null && System.currentTimeMillis() - this.lastPush > 500) {
                this.lastPush = System.currentTimeMillis();
                this.pusher.send("update");
            }
        }
    }

    /**
     * cancel a running import
     */
    public void cancel() {
        this.run = false;
    }

    /**
     * reads List with MappingFields from the configuration for a given Mapping
     * 
     * @param mappingName
     * @return
     */
    private List<MappingField> getMapping(String mappingName) {
        // find the correct mapping node
        HierarchicalConfiguration mappingNode = null;
        for (HierarchicalConfiguration node : this.config.configurationsAt("mappingSet")) {
            String name = node.getString("[@name]");
            if (name.equals(mappingName)) {
                mappingNode = node;
                break;
            }
        }
        // if mapping node was not found, send back error message
        if (mappingNode == null) {
            return Collections.emptyList();
        }

        // create a list of all fields to import
        List<MappingField> mappingFields = new ArrayList<>();
        List<HierarchicalConfiguration> fields = mappingNode.configurationsAt("field");
        try {
            for (HierarchicalConfiguration field : fields) {
                String column = field.getString("[@column]", null);
                String label = field.getString("[@label]", null);
                String mets = field.getString("[@mets]", null);
                String type = field.getString("[@type]", null);
                String separator = field.getString("[@separator]", ",");
                if ("space".equals(separator)) {
                    separator = " ";
                }
                boolean blankBeforeSeparator = field.getBoolean("[@blankBeforeSeparator]", false);
                boolean blankAfterSeparator = field.getBoolean("[@blankAfterSeparator]", false);
                String ead = field.getString("[@ead]", null);
                String gndColumn = field.getString("[@gndColumn]", null);
                String structureType = field.getString("[@structureType]", null);
                String target = field.getString("[@target]", null);
                mappingFields.add(new MappingField(column, label, mets, type, separator, blankBeforeSeparator, blankAfterSeparator, ead, gndColumn,
                        structureType, target));
            }
            return mappingFields;
        } catch (NullPointerException ex) {
            String message = "Invalid MappingSet configuration. Mandatory parameter missing! Please correct the configuration file! Import aborted!";
            log.error(message, ex);
            updateLog(message, 3);

        }
        return Collections.emptyList();
    }

    /**
     * reads the importset and the xls file with the processdescription
     * 
     * @param importSet
     * @param processFile
     * @return
     */
    private ProcessDescription getProcessDescription(Row processRow, ImportSet importSet, Path processFile) {
        Row processDescriptionRow = processRow;
        String mappingSet = importSet.isRowMode() ? importSet.getMapping() : importSet.getDescriptionMappingSet();
        if (importSet.getImportSetDescription() != null || processDescriptionRow != null) {
            List<MappingField> processMetadata = getMapping(mappingSet);
            List<MappingField> processDescription = new ArrayList<>();
            HashMap<String, String> processProperties = new HashMap<>();

            MappingField fileNameColumn = null;
            if (processMetadata.isEmpty()) {
                updateLog("No valid ImportSetDescription with the Name: " + mappingSet + " was found!", 3);
                this.failedImports.add(processFile.getFileName().toString());
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
                if ("FileName".equals(field.getType())) {
                    fileNameColumn = field;
                    break;
                }
            }

            // if we are in row mode we don't need to read a description mapping set an can leave
            if (importSet.isRowMode()) {
                for (MappingField field : processDescription) {
                    processProperties.put(field.getType(), XlsReader.getCellContent(processDescriptionRow, field));
                }
                return new ProcessDescription(processDescriptionRow, processMetadata, processProperties, processFile.getFileName());
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
                        this.failedImports.add(processFile.getFileName().toString());
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
        this.errorList = new ArrayList<>();
        this.failedImports = new ArrayList<>();
        StorageProviderInterface storageProvider = StorageProvider.getInstance();
        updateLog("Start import for: " + importSet.getName());
        this.progress = 0;

        // read mappings

        List<MappingField> mappingFields = getMapping(importSet.getMapping());
        if (mappingFields.isEmpty()) {
            updateLog("Import could not be executed because no MappingSet with the name " + importSet.getMapping() + "  was found!", 3);
            return;
        }

        // create folder for processed files
        Path processedFolder = Paths.get(importSet.getMetadataFolder(), "processed");
        if (!storageProvider.isFileExists(processedFolder)) {
            try {
                storageProvider.createDirectories(processedFolder);
            } catch (IOException e) {
                updateLog("Error creating Folder for processed xls documents! Export aborted " + e.getMessage(), 3);
                return;
            }
        }
        Path failureFolder = Paths.get(importSet.getMetadataFolder(), "error");
        if (!storageProvider.isFileExists(failureFolder)) {
            try {
                storageProvider.createDirectories(failureFolder);
            } catch (IOException e) {
                updateLog("Error creating Folder for xls documents of failed Imports! Export aborted " + e.getMessage(), 3);
                return;
            }
        }

        // run the import in a separate thread to allow a dynamic progress bar
        this.run = true;
        Runnable runnable;

        if (!importSet.isRowMode()) {
            runnable = () -> {

                // create list with files in metadata folder of importSet
                List<Path> filesToRead = storageProvider.listFiles(importSet.getMetadataFolder(), HuImporterWorkflowPlugin::isRegularAndNotHidden);
                updateLog("Run through all import files");
                this.itemsTotal = filesToRead.size();
                this.itemCurrent = 0;
                Process process = null;
                try {

                    if (filesToRead.isEmpty()) {
                        updateLog("There are no files in the folder: " + importSet.getMetadataFolder(), 3);
                    }
                    for (Path processFile : filesToRead) {
                        this.successful = true;
                        ProcessDescription processDescription = getProcessDescription(null, importSet, processFile);
                        if (processDescription == null && !StringUtils.isBlank(importSet.getImportSetDescription())) {
                            updateLog("A importSetDescription was configured but there were Errors getting the Description!", 3);
                            continue;
                        }

                        Thread.sleep(100);
                        if (!this.run) {
                            break;
                        }
                        updateLog("Datei: " + processFile.toString());

                        try {
                            Set<Path> imageFiles = null;
                            if (importSet.getMediaFolder() != null) {
                                // TODO catch IOException here!
                                imageFiles = filterImagesInFolder(importSet.getMediaFolder());
                            } else {
                                // check if this is desired behavior!
                                updateLog("No mediaFolder specified! Import aborted!", 3);
                                this.failedImports.add(processFile.getFileName().toString());
                                continue;
                            }
                            // create Process, DocumentManager and EadManager
                            DocumentManager dManager = new DocumentManager(processDescription, importSet, this);
                            process = dManager.getProcess();
                            EadManager eadManager = null;
                            String nodeId = null;
                            if (StringUtils.isNotBlank(importSet.getEadFile())) {
                                eadManager = new EadManager(importSet, process.getTitel(), dManager.getCatalogIDDIgital());
                                if (eadManager.isDbStatusOk()) {
                                    nodeId = eadManager.addDocumentNodeWithMetadata(processDescription.getRow(),
                                            processDescription.getMetaDataMapping());
                                } else {
                                    updateLog("Couldn't open baseX-DB as the database is locked! No EAD-Entries were generated for this process!", 3);
                                }
                            }
                            if (importSet.getProcessTitleMode().toUpperCase() == "EAD") {
                                if (nodeId == null) {
                                    updateLog("processTitleMode EAD specified but no EAD NodeId was generated.", 3);
                                    this.failedImports.add(processFile.getFileName().toString());
                                    continue;
                                }
                            }

                            updateLog("Start importing: " + process.getTitel(), 1);

                            if (processDescription != null && processDescription.getMetaDataMapping() != null) {

                                try {
                                    dManager.addMetadataFromRowToTopStruct(processDescription.getRow(), processDescription.getMetaDataMapping(),
                                            imageFiles, nodeId);
                                } catch (MetadataTypeNotAllowedException e) {
                                    updateLog("Metadatatype CatalogIDDigital not allowed for TopStruct. Please update the ruleset.", 3);
                                }
                            }

                            //Try to open File if IOException flies here, no process will be created
                            XlsReader reader = new XlsReader(processFile.toString());
                            Sheet sheet = reader.getSheet();
                            for (Row row : sheet) {
                                // skip rows until start row
                                if (row.getRowNum() < importSet.getRowStart() - 1) {
                                    continue;
                                }
                                String subnodeId = null;
                                //only add ead subnodes if a SubnodeType is specified
                                if (StringUtils.isNotBlank(importSet.getEadSubnodeType()) && eadManager.isDbStatusOk()) {
                                    subnodeId = eadManager.addSubnodeWithMetaData(row, mappingFields);
                                }
                                // end parsing after end row number
                                if (importSet.getRowEnd() != 0 && row.getRowNum() > importSet.getRowEnd()) {
                                    break;
                                }

                                // create the metadata fields by reading the config (and get content from the
                                // content files)
                                dManager.createStructureWithMetaData(row, mappingFields, imageFiles, subnodeId);

                            }
                            // close workbook
                            reader.closeWorkbook();

                            // write the metsfile
                            dManager.writeMetadataFile();
                            updateLogAndProcess(process.getId(), "Process automatically created by " + getTitle() + " with ID: " + process.getId(),
                                    1);
                            if (this.successful) {
                                // start any open automatic tasks for the created process
                                for (Step s : process.getSchritteList()) {
                                    if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum()) && s.isTypAutomatisch()) {
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
                                    if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum())) {
                                        s.setBearbeitungsstatusEnum(StepStatus.ERROR);
                                        break;
                                    }
                                }
                                this.failedImports.add(processFile.getFileName().toString());
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
                        } catch (IOException | TypeNotAllowedAsChildException | TypeNotAllowedForParentException | PreferencesException
                                | SwapException | WriteException | DAOException e) {

                            String message = (process != null) ? "Error mapping and importing data during the import of process: "
                                    : "Error creating a process during import";

                            log.error("Error  during the import for process", e);
                            if (process != null) {
                                message = message + process.getTitel() + " " + e.getMessage();
                                updateLogAndProcess(process.getId(), message, 3);
                                try {
                                    ProcessManager.saveProcess(process);
                                } catch (DAOException e1) {

                                    e1.printStackTrace();
                                }
                            } else {
                                updateLog(message, 3);
                            }
                        }

                        // recalculate progress
                        this.itemCurrent++;
                        this.progress = 100 * this.itemCurrent / this.itemsTotal;
                        updateLog("Processing of record done.");
                    }

                    // finally last push
                    this.run = false;
                    Thread.sleep(1000);
                    updateLog("Import completed.", 2);
                    if (!this.failedImports.isEmpty()) {
                        updateLog("We encountered errors during the import. Please check the logfile and the process logs!", 3);
                        updateLog(this.failedImports.size() + " Import(s) finished with errors!", 3);
                        this.failedImports.forEach((importFile) -> {
                            updateLog(importFile, 3);
                        });
                    }

                } catch (InterruptedException e) {
                    Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                    log.error("Error trying to execute the import", e);

                }

                this.pusher.send("summary");
            };
        } else {
            runnable = () -> {
                // create list with files in metadata folder of importSet
                List<Path> filesToRead = storageProvider.listFiles(importSet.getMetadataFolder(), HuImporterWorkflowPlugin::isRegularAndNotHidden);
                updateLog("Run through all import files");
                Process process = null;
                try {

                    if (filesToRead.isEmpty()) {
                        updateLog("There are no files in the folder: " + importSet.getMetadataFolder(), 3);
                    }
                    fileLoop: for (Path processFile : filesToRead) {
                        XlsReader reader = new XlsReader(processFile.toString());
                        Sheet sheet = reader.getSheet();
                        this.itemCurrent = 0;
                        this.itemsTotal = sheet.getLastRowNum();
                        for (Row row : sheet) {
                            // skip rows until start row
                            if (row.getRowNum() < importSet.getRowStart() - 1) {
                                continue;
                            }

                            // end parsing after end row number
                            if (importSet.getRowEnd() != 0 && row.getRowNum() >= importSet.getRowEnd()) {
                                break;
                            }

                            this.successful = true;
                            ProcessDescription processDescription = getProcessDescription(row, importSet, processFile);
                            if (processDescription == null) {
                                updateLog("Error reading Process metadata from row: " + row.getRowNum(), 3);
                                continue;
                            }

                            Thread.sleep(100);
                            if (!this.run) {
                                break;
                            }
                            updateLog("Datei: " + processFile.toString() + " Zeile: " + row.getRowNum());

                            try {
                                Set<Path> imageFiles = null;
                                if (importSet.getMediaFolder() != null) {
                                    // TODO catch IOException here!
                                    imageFiles = filterImagesInFolder(importSet.getMediaFolder());
                                } else {
                                    // check if this is desired behavior!
                                    updateLog("No mediaFolder specified! Import aborted!", 3);
                                    this.failedImports.add(processFile.getFileName().toString());
                                    continue fileLoop;
                                }
                                // create Process, DocumentManager and EadManager
                                DocumentManager dManager = new DocumentManager(processDescription, importSet, this);
                                process = dManager.getProcess();
                                EadManager eadManager = null;
                                String nodeId = null;
                                if (StringUtils.isNotBlank(importSet.getEadFile())) {
                                    eadManager = new EadManager(importSet, process.getTitel(), dManager.getCatalogIDDIgital());
                                    if (eadManager.isDbStatusOk()) {
                                        nodeId = eadManager.addDocumentNodeWithMetadata(processDescription.getRow(),
                                                processDescription.getMetaDataMapping());
                                    } else {
                                        updateLog("Couldn't open baseX-DB as the database is locked! No EAD-Entries were generated for this process!",
                                                3);
                                    }
                                }
                                if (importSet.getProcessTitleMode().toUpperCase() == "EAD") {
                                    if (nodeId == null) {
                                        updateLog("processTitleMode EAD specified but no EAD NodeId was generated.", 3);
                                        this.failedImports.add(processFile.getFileName().toString());
                                        continue;
                                    }
                                }

                                updateLog("Start importing: " + process.getTitel(), 1);

                                if (processDescription != null && processDescription.getMetaDataMapping() != null) {
                                    try {
                                        dManager.addMetadataFromRowToTopStruct(processDescription.getRow(), processDescription.getMetaDataMapping(),
                                                imageFiles, nodeId);
                                    } catch (TypeNotAllowedForParentException e) {
                                        // this one catches an exception thrown by addMetadataFromRowToTopStruct
                                        updateLog(
                                                "Type not allowed for Parent! Couldn't add substructure for image files. Please update the ruleset!",
                                                3);
                                    } catch (MetadataTypeNotAllowedException e) {
                                        updateLog("Metadatatype CatalogIDDigital not allowed for TopStruct. Please update the ruleset.", 3);
                                    }
                                }
                                // write the metsfile
                                dManager.writeMetadataFile();
                                updateLogAndProcess(process.getId(),
                                        "Process automatically created by " + getTitle() + " with ID: " + process.getId(), 1);

                                if (this.successful) {
                                    // start any open automatic tasks for the created process
                                    for (Step s : process.getSchritteList()) {
                                        if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum()) && s.isTypAutomatisch()) {
                                            ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                                            myThread.startOrPutToQueue();
                                        }
                                    }

                                } else {
                                    for (Step s : process.getSchritteList()) {
                                        if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum())) {
                                            s.setBearbeitungsstatusEnum(StepStatus.ERROR);
                                            break;
                                        }
                                    }
                                    this.failedImports.add(processFile.getFileName().toString() + " ROW: " + row.getRowNum());
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
                            } catch (IOException | TypeNotAllowedAsChildException | PreferencesException | SwapException | WriteException
                                    | DAOException e) {

                                String message = (process != null) ? "Error mapping and importing data during the import of process: "
                                        : "Error creating a process during import";

                                log.error("Error  during the import for process", e);
                                if (process != null) {
                                    message = message + process.getTitel() + " " + e.getMessage();
                                    updateLogAndProcess(process.getId(), message, 3);
                                    try {
                                        ProcessManager.saveProcess(process);
                                    } catch (DAOException e1) {

                                        e1.printStackTrace();
                                    }
                                } else {
                                    updateLog(message, 3);
                                }
                            }
                            // recalculate progress
                            this.itemCurrent++;
                            this.progress = 100 * this.itemCurrent / this.itemsTotal;
                        }

                        updateLog("Processing of " + processFile.getFileName() + " finished.", 2);

                        //end of document loop
                        if (this.successful) {
                            // move parsed xls to processed folder
                            storageProvider.move(processFile, Paths.get(processedFolder.toString(), processFile.getFileName().toString()));

                        } else {
                            // move parsed xls to failure folder
                            storageProvider.move(processFile, Paths.get(failureFolder.toString(), processFile.getFileName().toString()));
                        }
                    }

                    // finally last push
                    this.run = false;
                    Thread.sleep(1000);
                    updateLog("Import completed.", 2);
                    if (!this.failedImports.isEmpty()) {
                        updateLog("We encountered errors during the import. Please check the logfile and the process logs!", 3);
                        updateLog(this.failedImports.size() + " Import(s) finished with errors!", 3);
                        this.failedImports.forEach((importFile) -> {
                            updateLog(importFile, 3);
                        });
                    }

                } catch (InterruptedException | IOException e) {
                    Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                    log.error("Error trying to execute the import", e);
                }

                this.pusher.send("summary");
            };
        }
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
            return !Files.isDirectory(path) && !Files.isHidden(path) && path.toString().endsWith(".xlsx");
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

        Helper.addMessageToProcessJournal(processId, type, message);
        updateLog(message, level);
    }

    /**
     * simple method to send status message with specific level to gui
     * 
     * @param logmessage
     */
    public void updateLog(String logmessage, int level) {
        LogMessage message = new LogMessage(logmessage, level);
        this.logQueue.add(message);
        if (level == 3) {
            this.errorList.add(message);
            this.successful = false;
        }
        log.debug(logmessage);
        if (this.pusher != null && System.currentTimeMillis() - this.lastPush > 500) {
            this.lastPush = System.currentTimeMillis();
            this.pusher.send("update");
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
        private String gndColumn;
        private String structureType;
        private String target;
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
        private String processTitleMode;
        private String importSetDescription;
        private String descriptionMappingSet;
        private String eadType;
        private String eadFile;
        private String eadNode;
        private String eadSubnodeType;
        private boolean rowMode;
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
        private HashMap<String, String> processProperties;
        private Path fileName;
    }
}
