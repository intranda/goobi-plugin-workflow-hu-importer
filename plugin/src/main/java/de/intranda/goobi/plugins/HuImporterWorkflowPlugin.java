package de.intranda.goobi.plugins;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
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
    private XMLConfiguration config=null;
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
    private String importFolder;
    private String workflow;
    private String publicationType;
    
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
    	updateLog("HotSwap is working");
    	updateLog("HotSwap is working");
    	
    	config = ConfigPlugins.getPluginConfig(title);
        // read some main configuration
        importFolder = config.getString("importFolder");
        workflow = config.getString("workflow");
        publicationType = config.getString("publicationType");
        
        // read list of mapping configuration
        importSets = new ArrayList<ImportSet>();
        List<HierarchicalConfiguration> mappings = config.configurationsAt("importSet");
        for (HierarchicalConfiguration node : mappings) {
            String name = node.getString("[@name]", "-");
            String metadataFolder = node.getString("[@metadataFolder]", "-");
            String mediaFolder = node.getString("[@mediaFolder]", "-");
            String workflow = node.getString("[@workflow]", "-");
            String project = node.getString("[@project]", "-");
            String mapping = node.getString("[@mapping]", "-");
            String publicationType =node.getString("[@publicationType]", "-");
            importSets.add(new ImportSet(name, metadataFolder, mediaFolder, workflow, project, mapping, publicationType));
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
        for (HierarchicalConfiguration node : config.configurationsAt("mapping")) {
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
            mappingFields.add(new MappingField(column, label, mets, metsGroup, ead, type, separator, blankBeforeSeparator, blankAfterSeparator, useAsProcessTitle, person));
        }
        //log.debug("List of fields: " + importfields);
        
        // run the import in a separate thread to allow a dynamic progress bar
        run = true;
        Runnable runnable = () -> {
            
            // read input file
            try {
            	updateLog("Run through all import files");
                int start = 0;
                int end = 20;
                itemsTotal = end - start;
                itemCurrent = start;
                
                // run through import files (e.g. from importFolder)
                for (int i = start; i < end; i++) {
                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }

                    // create a process name (here as UUID) and make sure it does not exist yet
                    String processname = UUID.randomUUID().toString();  
                    String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();
                    processname = processname.replaceAll(regex, "_").trim();   
                    
                    if (ProcessManager.countProcessTitle(processname, null) > 0) {
                        int tempCounter = 1;
                        String tempName = processname + "_" + tempCounter;
                        while(ProcessManager.countProcessTitle(tempName, null) > 0) {
                            tempCounter++;
                            tempName = processname + "_" + tempCounter;
                        }
                        processname = tempName;
                    }
                	updateLog("Start importing: " + processname, 1);

                    try {
                        // get the correct workflow to use
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

                        // add the logical basics
                        DocStruct logical = dd.createDocStruct(prefs.getDocStrctTypeByName(publicationType));
                        dd.setLogicalDocStruct(logical);

                        // create the metadata fields by reading the config (and get content from the content files of course)
                        for (MappingField mappingField : mappingFields) {
                            // treat persons different than regular metadata
                                             
                            if (mappingField.isPerson()) {
                            	updateLog("Add person '" + mappingField.getMets() + "' with value '" + mappingField.getColumn() + "'");
                                Person p = new Person(prefs.getMetadataTypeByName(mappingField.getMets()));
                                String firstname = mappingField.getMets().substring(0, mappingField.getColumn().indexOf(" "));
                                String lastname = mappingField.getMets().substring(mappingField.getColumn().indexOf(" "));
                                p.setFirstname(firstname);
                                p.setLastname(lastname);
                                logical.addPerson(p);       
                            } else {
                            	updateLog("Add metadata '" + mappingField.getMets() + "' with value '" + mappingField.getColumn() + "'");
                                Metadata mdTitle = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                                mdTitle.setValue(mappingField.getColumn());
                                logical.addMetadata(mdTitle);
                            }
                            updateLog("TEST: "+ mappingField.getColumn() +" "+mappingField.getMets());
                        }
                        
                        for (String Path: StorageProvider.getInstance().list("/opt/digiverso/import/sample")) {
                            updateLog("Datei: "+Path);
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
                        File pdf = new File(importFolder, "file.pdf");
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
                        Helper.setFehlerMeldung("Error while creating a process during the import: " + e.getMessage());
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
            } catch (InterruptedException e) {
                Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                log.error("Error while trying to execute the import", e);
                updateLog("Error while trying to execute the import: " + e.getMessage(), 3);
            }

        };
        new Thread(runnable).start();
    }

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;
    }

	/**
	 * simple method to send status message to gui
	 * @param logmessage
	 */
	private void updateLog(String logmessage) {
		updateLog(logmessage, 0);
	}
	
	/**
	 * simple method to send status message with specific level to gui
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
        private boolean person;
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
