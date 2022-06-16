package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.goobi.beans.Process;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ImportSet;
import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.MappingField;
import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ProcessDescription;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import lombok.Getter;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

public class DocumentManager {
    @Getter
    private Process process;
    @Getter
    private Prefs prefs;
    private HuImporterWorkflowPlugin plugin;

    private Fileformat fileformat;
    private DigitalDocument digitalDocument;
    private DocStruct logical;
    private DocStruct physical;
    private ImportSet importSet;
    private int PageCount = 0;
    private DocStruct structure;

    public DocumentManager(ProcessDescription processDescription, ImportSet importSet, HuImporterWorkflowPlugin plugin)
            throws ProcessCreationException {
        this.plugin = plugin;
        this.importSet = importSet;
        BeanHelper bhelp = new BeanHelper();
        HashMap<String, String> processProperties = processDescription.getProcessProperties();

        String processname = null;
        if (processProperties != null) {
            processname = processProperties.get(ProcessProperties.PROCESSNAME.toString());
        }

        String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();

        // if processname field was empty use filename UUID
        if (StringUtils.isBlank(processname))
            processname = UUID.randomUUID().toString();

        // if UseAsProcessTitle is set use Filename as ProcessTitle
        if (importSet.isUseFileNameAsProcessTitle()) {
            String filename = processDescription.getFileName().toString();
            if (filename.contains(".")) {
                filename = filename.substring(0, filename.lastIndexOf("."));
            }
            processname = filename.replaceAll(regex, "_").trim();
        }
        if (ProcessManager.countProcessTitle(processname, null) > 0) {
            int tempCounter = 1;
            String tempName = processname + "_" + tempCounter;
            while (ProcessManager.countProcessTitle(tempName, null) > 0) {
                tempCounter++;
                tempName = processname + "_" + tempCounter;
            }
            processname = tempName;
        }
        try {
            String workflow = importSet.getWorkflow();
            Process template = ProcessManager.getProcessByExactTitle(workflow);
            this.prefs = template.getRegelsatz().getPreferences();
            Fileformat ff = new MetsMods(this.prefs);
            DigitalDocument dd = new DigitalDocument();
            ff.setDigitalDocument(dd);

            // add the physical basics
            DocStruct physical = dd.createDocStruct(this.prefs.getDocStrctTypeByName("BoundBook"));
            dd.setPhysicalDocStruct(physical);

            DocStruct logic = dd.createDocStruct(this.prefs.getDocStrctTypeByName(importSet.getPublicationType()));
            dd.setLogicalDocStruct(logic);
            MetadataType MDTypeForPath = this.prefs.getMetadataTypeByName("pathimagefiles");

            // save the process
            Process process = bhelp.createAndSaveNewProcess(template, processname, ff);
            plugin.updateLog("Process successfully created with ID: " + process.getId(), 2);

            // add some properties
            bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
            bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());

            String projectName = importSet.getProject();
            if (!StringUtils.isBlank(projectName)) {
                try {
                    ProjectManager.getProjectByName(projectName);
                } catch (DAOException e) {
                    plugin.updateLog(
                            "A Project with the name: " + projectName + " does not exist. Please update the configuration or create the Project.", 3);
                }
            }
            this.process = process;
            // read fileformat etc. from process
            this.fileformat = this.process.readMetadataFile();
            this.digitalDocument = this.fileformat.getDigitalDocument();
            this.logical = this.digitalDocument.getLogicalDocStruct();
            this.physical = this.digitalDocument.getPhysicalDocStruct();

            // add imagepath:
            Metadata imagePath = new Metadata(this.prefs.getMetadataTypeByName("pathimagefiles"));
            imagePath.setValue(process.getImagesDirectory());
            this.physical.addMetadata(imagePath);

        } catch (PreferencesException | TypeNotAllowedForParentException | ReadException | WriteException | IOException | InterruptedException
                | SwapException | DAOException | MetadataTypeNotAllowedException | DocStructHasNoTypeException ex) {
            throw new ProcessCreationException(ex);
        }
    }
    
    public void addNodeIdToTopStruct(String nodeId) throws MetadataTypeNotAllowedException {
    	addNodeId(logical,nodeId);
    }
    private void addNodeId(DocStruct ds, String nodeId) throws MetadataTypeNotAllowedException {
    	if (StringUtils.isNotBlank(nodeId)) {
    		Metadata nodeid = new Metadata(prefs.getMetadataTypeByName("NodeId"));
            nodeid.setValue(nodeId);
			ds.addMetadata(nodeid);
    	}
    }

    public void addMetaDataToTopStruct(MappingField mappingField, String cellContent)
            throws MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        addMetadata(logical, mappingField, cellContent);
    }

    public void addMetadataToStructure(MappingField mappingField, String cellContent)
            throws TypeNotAllowedForParentException, MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        addMetadata(structure, mappingField, cellContent);
    }

    public void createStructure(String strucType) throws TypeNotAllowedForParentException {
        structure = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(strucType));
    }

    public void createStructureWithMetaData(Row row, List<MappingField> mappingFields, Set<Path> imageFiles, String nodeId)
            throws TypeNotAllowedForParentException, TypeNotAllowedAsChildException, IOException, InterruptedException, SwapException, DAOException {
        createStructure(importSet.getStructureType());
        for (MappingField mappingField : mappingFields) {

            String cellContent = XlsReader.getCellContent(row, mappingField);

            if (StringUtils.isNotBlank(mappingField.getType()) && StringUtils.isNotBlank(cellContent)) {
                if (mappingField.getType().trim().equals("media")) {
                    addMediaFile(cellContent, imageFiles);
                } else {
                    try {
                        addMetadataToStructure(mappingField, cellContent);
                    } catch (MetadataTypeNotAllowedException e) {
                        plugin.updateLogAndProcess(process.getId(),
                                "Invalid Mapping for Field " + mappingField.getType() + " in MappingSet " + importSet.getMapping(), 3);
                    }
                }
            }
        }
        try {
			addNodeId(structure,nodeId);
		} catch (MetadataTypeNotAllowedException e) {
			plugin.updateLogAndProcess(process.getId(),"Metadata field definition for nodeId is missing in the structure type (needed to link document with ead-nodes)! Please update the ruleset.", 3);
		}
        logical.addChild(structure);
    }

    public void saveProcess() throws DAOException {
        ProcessManager.saveProcess(process);
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
     * @throws TypeNotAllowedAsChildException
     */
    public void addMetadata(DocStruct ds, MappingField mappingField, String cellContent)
            throws MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        switch (mappingField.getType()) {
            case "person":
                if (StringUtils.isBlank(mappingField.getMets())) {
                    if (StringUtils.isBlank(mappingField.getEad())) {
                        plugin.updateLogAndProcess(process.getId(), "No Mets provided. Please update the Mapping " + importSet.getMapping(), 3);
                    }
                    return;
                }
                plugin.updateLog("Add person '" + mappingField.getMets() + "' with value '" + cellContent + "'");
                Person p = new Person(prefs.getMetadataTypeByName(mappingField.getMets()));
                String firstname = cellContent.substring(0, cellContent.indexOf(" "));
                String lastname = cellContent.substring(cellContent.indexOf(" "));
                p.setFirstname(firstname);
                p.setLastname(lastname);
                ds.addPerson(p);
                break;
            case "metadata":
                if (StringUtils.isBlank(mappingField.getMets())) {
                    if (StringUtils.isBlank(mappingField.getEad())) {
                        plugin.updateLogAndProcess(process.getId(), "No Mets provided. Please update the Mapping " + importSet.getMapping(), 3);
                    }
                    return;
                }
                Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                md.setValue(cellContent);
                ds.addMetadata(md);
                break;
            case "FileName":
                //do nothhing
                break;
            default:
                plugin.updateLogAndProcess(process.getId(), "the specified type: " + mappingField.getType() + " is not supported", 3);
                return;
        }
    }

    public void addMediaFile(String cellContent, Set<Path> imageFiles)
            throws IOException, InterruptedException, SwapException, DAOException, TypeNotAllowedForParentException, TypeNotAllowedAsChildException {
        StorageProviderInterface storageProvider = StorageProvider.getInstance();
        String[] imageFileNames = cellContent.split(",");
        for (String imageFileName : imageFileNames) {
            Path imageFile = imageFiles.stream().filter(path -> path.getFileName().toString().equals(imageFileName.trim())).findFirst().orElse(null);
            if (imageFile == null) {
                plugin.updateLogAndProcess(process.getId(), "Couldn't find the following file: " + importSet.getMediaFolder() + imageFileName, 3);
            } else {
                Path masterFolder = Paths.get(process.getImagesOrigDirectory(false));
                if (!storageProvider.isFileExists(masterFolder))
                    storageProvider.createDirectories(masterFolder);
                if (Files.isReadable(imageFile)) {
                    storageProvider.copyFile(imageFile, Paths.get(masterFolder.toString(), imageFile.getFileName().toString()));
                    if (!addPage(structure, imageFile.toFile())) {
                        plugin.updateLogAndProcess(process.getId(), "Couldn't add Page to Structure", 3);
                    }

                } else {
                    plugin.updateLogAndProcess(process.getId(), "Couldn't read the following file: " + importSet.getMediaFolder() + imageFileName, 3);
                }
            }
        }
    }

    /**
     * adds page to the physical docstruct and links it to the logical docstruct-element
     * 
     * @param ds
     * @param dd
     * @param imageFile
     * @return true if successful
     */
    private boolean addPage(DocStruct ds, File imageFile) {
        try {
            DocStructType newPage = prefs.getDocStrctTypeByName("page");
            DocStruct dsPage = digitalDocument.createDocStruct(newPage);
            PageCount++;
            // physical page no
            physical.addChild(dsPage);
            MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
            Metadata mdTemp = new Metadata(mdt);
            mdTemp.setValue(String.valueOf(PageCount));
            dsPage.addMetadata(mdTemp);

            // logical page no
            mdt = prefs.getMetadataTypeByName("logicalPageNumber");
            mdTemp = new Metadata(mdt);

            mdTemp.setValue("uncounted");

            dsPage.addMetadata(mdTemp);
            ds.addReferenceTo(dsPage, "logical_physical");

            // image name
            ContentFile cf = new ContentFile();

            cf.setLocation("file://" + imageFile.getName());

            dsPage.addContentFile(cf);
            if (PageCount % 10 == 0) {
                plugin.updateLog("Created " + PageCount + "physical Pages for Process with Id: " + process.getId());
            }
            return true;
        } catch (TypeNotAllowedAsChildException | TypeNotAllowedForParentException e) {
            plugin.updateLogAndProcess(1, "Error creating page - type not allowed as child/for parent", 3);
            return false;
        } catch (MetadataTypeNotAllowedException e) {
            plugin.updateLogAndProcess(1, "Error creating page - Metadata type not allowed", 3);
            return false;
        }
    }

    public void writeMetadataFile() throws WriteException, PreferencesException, IOException, InterruptedException, SwapException, DAOException {
        process.writeMetadataFile(fileformat);
    }

}
