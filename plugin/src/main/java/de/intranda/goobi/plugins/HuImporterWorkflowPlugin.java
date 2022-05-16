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
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
import ugh.dl.Prefs;
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
        readConfiguration();

    }

    /**
     * private method to read main configuration file
     */
    private void readConfiguration() {
        updateLog("Start reading the configuration");
        updateLog("HotSwap is working !!!");
        updateLog("HotSwap is working!!!");

        config = ConfigPlugins.getPluginConfig(title);

        // read list of ImportSet configuration
        importSets = new ArrayList<ImportSet>();
        List<HierarchicalConfiguration> mappings = config.configurationsAt("importSet");
        for (HierarchicalConfiguration node : mappings) {
            String name = node.getString("[@name]", "-");
            String metadataFolder = node.getString("[@metadataFolder]", "-");
            String mediaFolder = node.getString("[@mediaFolder]", "-");
            String workflow = node.getString("[@workflow]", "-");
            String project = node.getString("[@project]", "-");
            String mappingSet = node.getString("[@mappingSet]", "-");
            String publicationType = node.getString("[@publicationType]", "-");
            importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mappingSet, publicationType));
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
        for (HierarchicalConfiguration field : fields) {
            String column = field.getString("[@column]");
            String label = field.getString("[@label]");
            String mets = field.getString("[@mets]");
            String metsGroup = field.getString("[@metsGroup]");
            String ead = field.getString("[@ead]");
            String type = field.getString("[@type]");
            String separator = field.getString("[@separator]");
            boolean blankBeforeSeparator = field.getBoolean("[@blankBeforeSeparator]", false);
            boolean blankAfterSeparator = field.getBoolean("[@blankAfterSeparator]", false);
            boolean useAsProcessTitle = field.getBoolean("[@useAsProcessTitle]", false);
            boolean person = field.getBoolean("[@person]", false);
            mappingFields.add(new MappingField(column, label, mets, metsGroup, ead, type, separator, blankBeforeSeparator, blankAfterSeparator,
                    useAsProcessTitle));
        }
        //log.debug("List of fields: " + importfields);

        // run the import in a separate thread to allow a dynamic progress bar
        run = true;
        Runnable runnable = () -> {

            // read input file
            try {

                updateLog("I was here");
                List<Path> FilesToRead = StorageProvider.getInstance().listFiles(importSet.getMetadataFolder(), Files::isRegularFile);

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

                    //     int start = .getRowStart() - 1;
                    //     int end = .getRowEnd();
                    //                    if (end == 0) {
                    //                        end = sheet.getPhysicalNumberOfRows();
                    //                    }
                    int end = 0;

                    // create a process name (here as UUID) and make sure it does not exist yet
                    String processname = UUID.randomUUID().toString();
                    String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();
                    processname = processname.replaceAll(regex, "_").trim();

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
                        String publicationType = importSet.getPublicationType();
                        Process template = ProcessManager.getProcessByExactTitle(workflow);
                        Prefs prefs = template.getRegelsatz().getPreferences();
                        Fileformat fileformat = new MetsMods(prefs);
                        DigitalDocument dd = new DigitalDocument();
                        fileformat.setDigitalDocument(dd);

                        // add the physical basics
                        DocStruct physical = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
                        dd.setPhysicalDocStruct(physical);
                        Metadata mdForPath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
                        mdForPath.setValue("file:///");
                        physical.addMetadata(mdForPath);

                        for (Row row : sheet) {
                            // add the logical basics
                            DocStruct logical = dd.createDocStruct(prefs.getDocStrctTypeByName(publicationType));
                            dd.setLogicalDocStruct(logical);
                            
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
                                                logical.addPerson(p);
                                                break;
                                            case "media":
                                                //TODO implement
                                                break;
                                            default:
                                                updateLog("Add metadata '" + mappingField.getMets() + "' with value '" + mappingField.getColumn()
                                                        + "'");
                                                Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                                                md.setValue(cellContent);
                                                logical.addMetadata(md);
                                                break;
                                        }
                                    } else {
                                        updateLog("Add metadata '" + mappingField.getMets() + "' with value '" + mappingField.getColumn() + "'");
                                        Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                                        md.setValue(cellContent);
                                        logical.addMetadata(md);
                                    }

                                    updateLog("TEST: " + mappingField.getColumn() + " " + mappingField.getMets());
                                }
                            }
                        }

                        // save the process
                        Process process = bhelp.createAndSaveNewProcess(template, processname, fileformat);

                        // add some properties
                        bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
                        bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());
                        ProcessManager.saveProcess(process);
                    
                        // if media files are given, import these into the media folder of the process
                        updateLog("Start copying media files");
                        String targetBase = process.getImagesOrigDirectory(false);
                        File pdf = new File(importSet.metadataFolder, "file.pdf");
                        if (pdf.canRead()) {
                            StorageProvider.getInstance().createDirectories(Paths.get(targetBase));
                            StorageProvider.getInstance().copyFile(Paths.get(pdf.getAbsolutePath()), Paths.get(targetBase, "file.pdf"));
                        }
                      
                        // start any open automatic tasks for the created process
                        for (Step s : process.getSchritteList()) {
                            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                                myThread.startOrPutToQueue();
                            }
                        }
                        updateLog("Process successfully created with ID: " + process.getId());

                    } catch (Exception e) {
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
        private String column;
        private String label;
        private String mets;
        private String metsGroup;
        private String ead;
        private String type;
        private String separator;
        private boolean blankBeforeSeparator;
        private boolean blankAfterSeparator;
        private boolean useAsProcessTitle;
    }

    @Data
    @AllArgsConstructor
    public class ImportSet {
        private String name;
        private String metadataFolder;
        private String mediaFolder;
        private String workflow;
        private String project;
        private String mapping;
        private String publicationType;
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }
}
