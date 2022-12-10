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
import org.goobi.beans.Project;

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
        switch (importSet.getProcessTitleMode().toUpperCase()) {
            case "FILENAME":
                String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();
                String filename = processDescription.getFileName().toString();
                if (filename.contains(".")) {
                    filename = filename.substring(0, filename.lastIndexOf("."));
                }
                processname = filename.replaceAll(regex, "_").trim();
                break;
            case "EAD":
                // if EAD Mode was set the process property process name should have been updated.
            case "XLSX":
                processname = processProperties.get(ProcessProperties.PROCESSNAME.toString());
                break;
            case "UUID":
                // UUID is the default mode
            default:
                processname = UUID.randomUUID().toString();
                break;
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

            DocStructType dstype = this.prefs.getDocStrctTypeByName(importSet.getPublicationType());
            if (dstype == null) {
                throw new ProcessCreationException("Couldn't find publication type: " + importSet.getPublicationType() + " in the ruleset.");
            }
            DocStruct logic = dd.createDocStruct(dstype);
            dd.setLogicalDocStruct(logic);

            // save the process
            Process process = bhelp.createAndSaveNewProcess(template, processname, ff);
            plugin.updateLog("Process successfully created with ID: " + process.getId(), 2);

            // add some properties
            bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
            bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());

            Project project = null;
            String projectName = importSet.getProject();
            if (!StringUtils.isBlank(projectName)) {
                try {
                    project = ProjectManager.getProjectByName(projectName);
                    process.setProjekt(project);
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

        } catch (PreferencesException | TypeNotAllowedForParentException | ReadException | IOException | SwapException
                | MetadataTypeNotAllowedException | DocStructHasNoTypeException ex) {
            throw new ProcessCreationException(ex);
        }
    }

    public void addNodeIdToTopStruct(String nodeId) throws MetadataTypeNotAllowedException {
        addNodeId(logical, nodeId);
    }

    public void addCatalogueId(String id) throws MetadataTypeNotAllowedException {
        if (StringUtils.isNotBlank(id)) {
            Metadata cid = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
            cid.setValue(id);
            logical.addMetadata(cid);
        }
    }

    private void addNodeId(DocStruct ds, String nodeId) throws MetadataTypeNotAllowedException {
        if (StringUtils.isNotBlank(nodeId)) {
            Metadata nodeid = new Metadata(prefs.getMetadataTypeByName("NodeId"));
            nodeid.setValue(nodeId);
            ds.addMetadata(nodeid);
        }
    }

    public void addMetaDataToTopStruct(MappingField mappingField, String cellContent, String gndUri)
            throws MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        addMetadata(logical, mappingField, cellContent, gndUri);
    }

    public void addMetadataToStructure(MappingField mappingField, String cellContent, String gndUri)
            throws TypeNotAllowedForParentException, MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        addMetadata(structure, mappingField, cellContent, gndUri);
    }

    public void createStructure(String structType) throws TypeNotAllowedForParentException {
        DocStructType dsType = prefs.getDocStrctTypeByName(structType);
        if (dsType != null) {
            structure = digitalDocument.createDocStruct(dsType);
        } else {
            plugin.updateLogAndProcess(process.getId(), "Couldn't find DocStruct type: " + structType + " in the ruleset.", 3);
            throw new TypeNotAllowedForParentException("Couldn't find DocStruct type:" + structType + " in the ruleset.");
        }
    }

    public void createStructureWithMetaData(Row row, List<MappingField> mappingFields, Set<Path> imageFiles, String nodeId)
            throws TypeNotAllowedForParentException, TypeNotAllowedAsChildException, IOException, InterruptedException, SwapException, DAOException {
        // look if structureType is defined in table
        MappingField mFieldStructureType =
                mappingFields.stream().filter(mappingField -> "structureType".equals(mappingField.getType())).findFirst().orElse(null);
        String structureType = importSet.getStructureType();
        if (mFieldStructureType != null) {
            String cellContentType = XlsReader.getCellContent(row, mFieldStructureType);
            if (StringUtils.isNotEmpty(cellContentType)) {
                structureType = cellContentType;
            }
        }
        createStructure(structureType);

        for (MappingField mappingField : mappingFields) {

            String cellContent = XlsReader.getCellContent(row, mappingField);
            String gndUri = null;
            if (StringUtils.isNotBlank(mappingField.getGndColumn())) {
                gndUri = XlsReader.getCellContentSplit(row, mappingField.getGndColumn());
            }
            if (StringUtils.isNotBlank(mappingField.getType()) && StringUtils.isNotBlank(cellContent)) {
                if ("media".equals(mappingField.getType().trim())) {
                    addMediaFile(mappingField, cellContent, imageFiles);
                } else {
                    try {
                        addMetadataToStructure(mappingField, cellContent, gndUri);
                    } catch (MetadataTypeNotAllowedException e) {
                        plugin.updateLogAndProcess(process.getId(), "Invalid Mapping for Field " + mappingField.getType() + " in MappingSet "
                                + importSet.getMapping() + " for METs: " + mappingField.getMets(), 3);
                    }
                }
            }
        }
        try {
            addNodeId(structure, nodeId);
        } catch (MetadataTypeNotAllowedException e) {
            plugin.updateLogAndProcess(process.getId(),
                    "Metadata field definition for nodeId is missing in the structure type (needed to link document with ead-nodes)! Please update the ruleset.",
                    3);
        }
        logical.addChild(structure);
    }

    public void saveProcess() throws DAOException {
        ProcessManager.saveProcess(process);
    }

    private Person createPerson(String cellContent, MappingField mappingField) throws MetadataTypeNotAllowedException {
        Person p = new Person(prefs.getMetadataTypeByName(mappingField.getMets()));
        int index = cellContent.indexOf(mappingField.getSeparator());
        String firstpart;
        String lastpart;
        if (index > 0) {
            firstpart = cellContent.substring(0, index).trim();
            lastpart = cellContent.substring(index + 1).trim();

            if (" ".equals(mappingField.getSeparator())) {
                // should handle names like : Theodor Fontane
                p.setFirstname(firstpart);
                p.setLastname(lastpart);
            } else {
                // should handle names like : Fontane, Theodor
                p.setFirstname(lastpart);
                p.setLastname(firstpart);
            }
        } else {
            p.setLastname(cellContent);
        }
        return p;
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
    private void addMetadata(DocStruct ds, MappingField mappingField, String cellContent, String gndUri)
            throws MetadataTypeNotAllowedException, TypeNotAllowedAsChildException {
        switch (mappingField.getType()) {
            case "person":
                if (StringUtils.isBlank(mappingField.getMets())) {
                    if (StringUtils.isBlank(mappingField.getEad())) {
                        plugin.updateLogAndProcess(process.getId(), "No Mets provided. Please update the mapping " + importSet.getMapping(), 3);
                    }
                    return;
                }
                plugin.updateLog("Add person '" + mappingField.getMets() + "' with value '" + cellContent + "'");
                Person p = createPerson(cellContent, mappingField);
                if (mappingField.getGndColumn() != null) {
                    setAuthorityFile(p, gndUri);
                }
                ds.addPerson(p);
                break;
            case "metadata":
                if (StringUtils.isBlank(mappingField.getMets())) {
                    if (StringUtils.isBlank(mappingField.getEad())) {
                        plugin.updateLogAndProcess(process.getId(), "No Mets provided. Please update the mapping " + importSet.getMapping(), 3);
                    }
                    return;
                }
                Metadata md = new Metadata(prefs.getMetadataTypeByName(mappingField.getMets()));
                md.setValue(cellContent);
                if (mappingField.getGndColumn() != null) {
                    setAuthorityFile(md, gndUri);
                }
                try {
                    ds.addMetadata(md);
                } catch (DocStructHasNoTypeException ex) {
                    plugin.updateLogAndProcess(process.getId(),
                            "DocStruct has no type! This may happen if you specified an invalid type (i.e. Chapter) for sub elements", 3);
                }
                break;
            case "FileName":
                //do nothhing
                break;
            case "structureType":
                //do nothing
                break;
            default:
                plugin.updateLogAndProcess(process.getId(), "the specified type: " + mappingField.getType() + " is not supported", 3);
                return;
        }
    }

    private void addMediaFile(MappingField mappingField, String cellContent, Set<Path> imageFiles) throws IOException, SwapException, DAOException {
        StorageProviderInterface storageProvider = StorageProvider.getInstance();
        String[] imageFileNames = cellContent.split(mappingField.getSeparator());
        for (String imageFileName : imageFileNames) {
            if (StringUtils.isBlank(imageFileName)) {
                continue;
            }
            Path imageFile = imageFiles.stream().filter(path -> path.getFileName().toString().equals(imageFileName.trim())).findFirst().orElse(null);
            if (imageFile == null) {
                plugin.updateLogAndProcess(process.getId(), "Couldn't find the following file: " + importSet.getMediaFolder() + imageFileName, 3);
            } else {
                Path masterFolder = Paths.get(process.getImagesOrigDirectory(false));
                if (!storageProvider.isFileExists(masterFolder)) {
                    storageProvider.createDirectories(masterFolder);
                }
                if (Files.isReadable(imageFile)) {
                    storageProvider.copyFile(imageFile, Paths.get(masterFolder.toString(), imageFile.getFileName().toString()));
                    if (!addPage(structure, imageFile.toFile())) {
                        plugin.updateLogAndProcess(process.getId(), "Couldn't add page to structure", 3);
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
                plugin.updateLog("Created " + PageCount + " physical Pages for Process with Id: " + process.getId());
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

    private void setAuthorityFile(Metadata metadata, String gndUri) {
        if (StringUtils.isBlank(gndUri)) {
            return;
        }
        String gnd = null;
        int index = gndUri.lastIndexOf('/');
        if (index < 0) {
            // plugin.updateLogAndProcess(process.getId(), "Couldn't parse gndUri ", 3);
            // better to be optimistic, maybe it's a gnd without authority uri
            gnd = gndUri.trim();
        } else {
            gnd = gndUri.substring(index + 1);
        }
        if (StringUtils.isNotBlank(gnd)) {
            metadata.setAutorityFile("gnd", "http://d-nb.info/gnd/", gnd);
        }
    }

    public void writeMetadataFile() throws WriteException, PreferencesException, IOException, InterruptedException, SwapException, DAOException {
        process.writeMetadataFile(fileformat);
    }

}
