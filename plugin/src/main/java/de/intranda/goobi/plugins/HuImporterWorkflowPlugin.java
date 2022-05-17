package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPushPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.omnifaces.cdi.PushContext;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
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
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class HuImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {

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
            boolean useFileNameAsProcessTitle = node.getBoolean("[@useFileNameAsProcessTitle]", false);
            importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mappingSet, publicationType, structureType, rowStart,
                    rowEnd, useFileNameAsProcessTitle));
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
     * main method to start the actual import
     * 
     * @param importConfiguration
     */
    public void startImport(ImportSet importSet) {
        StorageProviderInterface storageProvider = StorageProvider.getInstance();
        updateLog("Start import for: " + importSet.getName());
        progress = 0;
        BeanHelper bhelp = new BeanHelper();

        // find the correct mapping node
        mappingNode = null;
        for (HierarchicalConfiguration node : config.configurationsAt("mappingSet")) {
            String name = node.getString("[@name]");
            if (name.equals(importSet.getMapping())) {
                log.debug("Configured mapping was found: " + name);
                mappingNode = node;
                break;
            }
        }
        // if mapping node was not found, send back error message
        if (mappingNode == null) {
            updateLog("Import could not be executed as no configuration node was found for " + importSet + importSet.getName() + " with mapping "
                    + importSet.getMapping());
            return;
        }

        // create a list of all fields to import
        List<MappingField> mappingFields = new ArrayList<MappingField>();
        List<HierarchicalConfiguration> fields = mappingNode.configurationsAt("field");
        try {
            for (HierarchicalConfiguration field : fields) {
                String column = field.getString("[@column]", null);
                String label = field.getString("[@label]", null);
                String mets = field.getString("[@mets]", null);
                String metsGroup = field.getString("[@metsGroup]", null);
                String ead = field.getString("[@ead]");
                String type = field.getString("[@type]", null);
                String separator = field.getString("[@separator]", ",");
                boolean blankBeforeSeparator = field.getBoolean("[@blankBeforeSeparator]", false);
                boolean blankAfterSeparator = field.getBoolean("[@blankAfterSeparator]", false);
                mappingFields.add(new MappingField(column, label, mets, metsGroup, ead, type, separator, blankBeforeSeparator, blankAfterSeparator));

            }
        } catch (NullPointerException ex) {
            String message = "Invalid MappingSet configuration. Mandatory parameter missing! Please correct the configuration file! Import aborted!";
            log.error(message, ex);
            updateLog(message, 3);
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

            // read input file
            try {
                List<Path> FilesToRead = storageProvider.listFiles(importSet.getMetadataFolder(), Files::isRegularFile);

                updateLog("Run through all import files");
                itemsTotal = FilesToRead.size();
                itemCurrent = 0;

                for (Path processFile : FilesToRead) {
                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }
                    updateLog("Datei: " + processFile.toString());
                    FileInputStream inputStream = new FileInputStream(new File(processFile.toString()));
                    Workbook workbook = new XSSFWorkbook(inputStream);
                    Sheet sheet = workbook.getSheetAt(0);

                    String processname = UUID.randomUUID().toString();

                    if (importSet.isUseFileNameAsProcessTitle()) {
                        processname = processFile.getFileName().toString();
                    }
                    String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();
                    processname = processname.substring(0, processname.lastIndexOf(".")).replaceAll(regex, "_").trim();
                    if (ProcessManager.countProcessTitle(processname, null) > 0) {
                        int tempCounter = 1;
                        String tempName = processname + "_" + tempCounter;
                        while (ProcessManager.countProcessTitle(tempName, null) > 0) {
                            tempCounter++;
                            tempName = processname + "_" + tempCounter;
                        }
                        processname = tempName;
                    }
                    updateLog("Start importing: " + processname, 1);

                    try {
                        // get the correct workflow to use
                        String workflow = importSet.getWorkflow();
                        Process template = ProcessManager.getProcessByExactTitle(workflow);
                        prefs = template.getRegelsatz().getPreferences();
                        Fileformat fileformat = new MetsMods(prefs);
                        DigitalDocument dd = new DigitalDocument();
                        fileformat.setDigitalDocument(dd);

                        // add the physical basics
                        DocStruct physical = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
                        dd.setPhysicalDocStruct(physical);

                        DocStruct logical = dd.createDocStruct(prefs.getDocStrctTypeByName(importSet.getPublicationType()));
                        dd.setLogicalDocStruct(logical);

                        MetadataType MDTypeForPath = prefs.getMetadataTypeByName("pathimagefiles");

                        // save the process
                        Process process = bhelp.createAndSaveNewProcess(template, processname, fileformat);

                        for (Row row : sheet) {
                            //skip rows until start row
                            if (row.getRowNum() < importSet.getRowStart() - 1)
                                continue;
                            //end parsing after end row number
                            if (importSet.getRowEnd() != 0 && row.getRowNum() > importSet.getRowEnd())
                                break;
                            //create new Docstruct of 
                            DocStruct ds = dd.createDocStruct(prefs.getDocStrctTypeByName(importSet.getStructureType()));

                            // Save media file, if defined
                            //TODO copy Media at once or one by one?
                            String media = null;
                            // create the metadata fields by reading the config (and get content from the content files of course)
                            for (MappingField mappingField : mappingFields) {
                                // treat persons different than regular metadata

                                String cellContent = getCellContent(row, mappingField);
                                if (StringUtils.isNotBlank(mappingField.getMets()) && StringUtils.isNotBlank(cellContent)) {
                                    if (StringUtils.isNotBlank(mappingField.getType())) {
                                        switch (mappingField.getType()) {
                                            case "person":
                                                updateLog("Add person '" + mappingField.getMets() + "' with value '" + cellContent + "'");
                                                Person p = new Person(prefs.getMetadataTypeByName(mappingField.getMets()));
                                                String firstname = cellContent.substring(0, cellContent.indexOf(" "));
                                                String lastname = cellContent.substring(cellContent.indexOf(" "));
                                                p.setFirstname(firstname);
                                                p.setLastname(lastname);
                                                ds.addPerson(p);
                                                break;
                                            case "media":
                                                //erzeuge Liste mit Dateinamen
                                                String imagesTifDirectory =  process.getImagesTifDirectory(false);
                                                List<Path> ImageFilePaths = getImageFiles(cellContent.split(","), importSet.getMediaFolder());
                                                //copy image
                                                physical.getAllMetadataByType(MDTypeForPath);
                                                List<? extends Metadata> filepath = physical.getAllMetadataByType(MDTypeForPath);
                                                Metadata mdForPath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
                                                physical.addMetadata(mdForPath);
                                                if (filepath == null || filepath.isEmpty()) {
                                                    mdForPath = new Metadata(MDTypeForPath);
                                                    physical.addMetadata(mdForPath);
                                                } else {
                                                    //why
                                                    mdForPath = filepath.get(0);
                                                }
                                                mdForPath.setValue("file://" +imagesTifDirectory);
                                                for (Path imageFilePath : ImageFilePaths) {
                                                   // addPage(pysical, log, dd, file, pageNo);
                                                }
                                            default:
                                                updateLog("Add metadata '" + mappingField.getMets() + "' with value '" + mappingField.getColumn()
                                                        + "'");
                                                Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                                                md.setValue(cellContent);
                                                ds.addMetadata(md);
                                                break;
                                        }
                                    } else {
                                        updateLog("Add metadata '" + mappingField.getMets() + "' with value '" + mappingField.getColumn() + "'");
                                        Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                                        md.setValue(cellContent);
                                        ds.addMetadata(md);
                                    }
                                    updateLog("TEST: " + cellContent + " " + mappingField.getMets());
                                    logical.addChild(ds);
                                }
                            }
                        }

                        // write the metsfile
                        process.writeMetadataAsTemplateFile(fileformat);

                        // add some properties
                        bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
                        bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());
                        ProcessManager.saveProcess(process);

                        // if media files are given, import these into the media folder of the process
                        updateLog("Start copying media files");
                        String targetBase = process.getImagesOrigDirectory(false);
                        File pdf = new File(importSet.metadataFolder, "file.pdf");
                        if (pdf.canRead()) {
                            storageProvider.createDirectories(Paths.get(targetBase));
                            storageProvider.copyFile(Paths.get(pdf.getAbsolutePath()), Paths.get(targetBase, "file.pdf"));
                        }

                        // start any open automatic tasks for the created process
                        for (Step s : process.getSchritteList()) {
                            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                                myThread.startOrPutToQueue();
                            }
                        }
                        //move parsed xls to processd folder
                        //TODO reactivate
                        //storageProvider.move(processFile, Paths.get(processedFolder.toString(),processFile.getFileName().toString()));

                        updateLog("Process successfully created with ID: " + process.getId());

                    } catch (Exception e) {
                        //Shouldn't we end the export here??
                        log.error("Error while creating a process during the import", e);
                        updateLog("Error while creating a process during the import: " + e.getMessage(), 3);
                        Helper.setFehlerMeldung("Error while creating a process during the import : " + e.getMessage());
                        pusher.send("error");
                    }

                    // recalculate progress
                    itemCurrent++;
                    progress = 100 * itemCurrent / itemsTotal;
                    updateLog("Processing of record done.");
                }

                // finally last push
                run = false;
                Thread.sleep(2000);
                updateLog("Import completed.");
            } catch (InterruptedException | IOException e) {
                Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                log.error("Error while trying to execute the import", e);
                updateLog("Error while trying to execute the import: " + e.getMessage(), 3);
            }

        };
        new Thread(runnable).start();

    }

    private List<Path> getImageFiles(String[] fileNames, String mediaFolder) {
        List<Path> paths = new ArrayList<Path>();
        for (String fileName : fileNames) {
            Path path = Paths.get(mediaFolder, fileName);
            if (StorageProvider.getInstance().isFileExists(path))
                ;
            paths.add(path);
        }
        return paths;
    }

    private void addPage(DocStruct physicaldocstruct, DocStruct logical, DigitalDocument dd, File imageFile, int pageNo)
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

            cf.setLocation("file://" + imageFile.getAbsolutePath());

            dsPage.addContentFile(cf);

        } catch (TypeNotAllowedAsChildException e) {
            log.error("Error creating Page", e);
        } catch (MetadataTypeNotAllowedException e) {
            log.error("Error creating Page", e);
        }
    }

    /**
     * Read content vom excel cell
     * 
     * @param row
     * @param cellname
     * @return
     */
    private String getCellContent(Row row, MappingField imf) {
        String[] cells = imf.getColumn().split(",");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String readCell = getCellContentSplit(row, cells[i]);
            if (StringUtils.isNotBlank(readCell)) {
                result.append(getCellContentSplit(row, cells[i]));
                if (StringUtils.isNotBlank(imf.getSeparator()) && i + 1 < cells.length) {
                    if (imf.blankBeforeSeparator) {
                        result.append(" ");
                    }
                    result.append(imf.getSeparator());
                    if (imf.blankAfterSeparator) {
                        result.append(" ");
                    }
                }
            }
        }
        return result.toString();
    }

    /**
     * Read content from excel cell as String
     * 
     * @param row
     * @param cellname
     * @return
     */
    private String getCellContentSplit(Row row, String cellname) {
        Cell cell = row.getCell(CellReference.convertColStringToIndex(cellname));
        if (cell != null) {
            DataFormatter dataFormatter = new DataFormatter();
            return dataFormatter.formatCellValue(cell).trim();
        }
        return null;
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

    /**
     * simple method to send status message with specific level to gui
     * 
     * @param logmessage
     */
    private void updateLog(String logmessage, int level) {
        logQueue.add(new LogMessage(logmessage, level));
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
        @NonNull
        private String mets;
        private String metsGroup;
        private String ead;
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
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }
}
