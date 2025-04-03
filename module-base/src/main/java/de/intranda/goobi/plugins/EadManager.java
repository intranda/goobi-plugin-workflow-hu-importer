package de.intranda.goobi.plugins;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.goobi.interfaces.IArchiveManagementAdministrationPlugin;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.IFieldValue;
import org.goobi.interfaces.IMetadataField;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ImportSet;
import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.MappingField;
import lombok.Getter;

public class EadManager {
    private IArchiveManagementAdministrationPlugin archivePlugin;
    private String processName;
    private String catalogIDDigital;
    private ImportSet importSet;
    private IEadEntry selectedNode = null;
    @Getter
    private boolean dbStatusOk;

    public EadManager(ImportSet importSet, String processName, String catalogIDDigital) {
        this.importSet = importSet;
        this.processName = processName;
        this.catalogIDDigital = catalogIDDigital;

        // find out if archive file is locked currently
        IPlugin ia = PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management");
        this.archivePlugin = (IArchiveManagementAdministrationPlugin) ia;

        // prepare ArchivePlugin
        this.archivePlugin.getPossibleDatabases();
        this.archivePlugin.setDatabaseName(importSet.getEadFile());
        this.archivePlugin.loadSelectedDatabase();
        this.dbStatusOk = checkDB();

        if (this.dbStatusOk) {
            try {
                this.selectedNode = findNode(importSet.getEadNode());
                if (this.selectedNode != null) {
                    this.archivePlugin.setSelectedEntry(this.selectedNode);
                }
            } catch (NullPointerException ex) {
                this.dbStatusOk = false;
            }
        }

    }

    private boolean checkDB() {
        List<String> possibleDBs = this.archivePlugin.getPossibleDatabases();
        return !possibleDBs.isEmpty() && StringUtils.isNotBlank(this.archivePlugin.getDatabaseName())
                && this.archivePlugin.getDatabaseName().equals(this.importSet.getEadFile());
    }

    private IEadEntry findNode(String eadNode) throws NullPointerException {
        return findNode(this.archivePlugin.getRootElement(), eadNode);
    }

    // TODO maybe add nodes of type Node
    private void addMetadata(IEadEntry entry, Row row, List<MappingField> mappingFields) {
        // create the metadata if the cell content is not empty
        for (MappingField field : mappingFields) {
            if (StringUtils.isNotBlank(field.getEad())) {
                String cellContent = XlsReader.getCellContent(row, field);
                if (StringUtils.isNotBlank(cellContent)) {
                    addEadMetadata(entry, field.getEad(), cellContent);
                }
            }
        }
    }

    public String addDocumentNodeWithMetadata(Row row, List<MappingField> mappingFields) {
        this.archivePlugin.addNode();
        IEadEntry entry = this.archivePlugin.getSelectedEntry();
        // set the prefered node type for the created node
        for (INodeType nt : this.archivePlugin.getConfig().getConfiguredNodes()) {
            if (nt.getNodeName().equals(this.importSet.getEadType())) {
                entry.setNodeType(nt);
            }
        }
        // use CatalogIDDigital as NodeID
        entry.setId(this.catalogIDDigital);

        addMetadata(entry, row, mappingFields);
        entry.setGoobiProcessTitle(entry.getId());

        this.archivePlugin.updateSingleNode();
        return entry.getId();
    }

    public void saveArchiveAndLeave() {
        this.archivePlugin.saveArchiveAndLeave();
    }

    public void cancelEdition() {
        this.archivePlugin.cancelEdition();
    }

    /**
     * run recursively through all nodes to find the right one
     * 
     * @param parent
     * @param id
     * @return
     */
    private IEadEntry findNode(IEadEntry parent, String id) {
        if (parent.getId().equals(id)) {
            return parent;
        } else if (parent.isHasChildren()) {
            for (IEadEntry child : parent.getSubEntryList()) {
                IEadEntry found = findNode(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * add metadata field to the right list
     * 
     * @param entry
     * @param fieldName
     * @param fieldValue
     */
    private void addEadMetadata(IEadEntry entry, String fieldName, String fieldValue) {
        if (addEadMetadata(fieldName, fieldValue, entry.getIdentityStatementAreaList())) {
            return;
        }
        if (addEadMetadata(fieldName, fieldValue, entry.getContextAreaList())) {
            return;
        }
        if (addEadMetadata(fieldName, fieldValue, entry.getContentAndStructureAreaAreaList())) {
            return;
        }
        if (addEadMetadata(fieldName, fieldValue, entry.getAccessAndUseAreaList())) {
            return;
        }
        if (addEadMetadata(fieldName, fieldValue, entry.getAlliedMaterialsAreaList())) {
            return;
        }
        if (addEadMetadata(fieldName, fieldValue, entry.getNotesAreaList())) {
            return;
        }

        addEadMetadata(fieldName, fieldValue, entry.getDescriptionControlAreaList());
    }

    /**
     * iterate through all metadata fields of a specific list
     * 
     * @param fieldName
     * @param fieldValue
     * @param list
     * @return
     */
    private boolean addEadMetadata(String fieldName, String fieldValue, List<IMetadataField> list) {
        for (IMetadataField field : list) {
            if (field.getName().equals(fieldName)) {

                IFieldValue value = field.createFieldValue();
                value.setValue(fieldValue.trim());
                field.setValues(Arrays.asList(value));
                return true;
            }
        }
        return false;
    }

    public String addSubnodeWithMetaData(Row row, List<MappingField> mappingFields) {
        String nodeType = this.importSet.getEadSubnodeType();
        IEadEntry parent = this.archivePlugin.getSelectedEntry();
        if (StringUtils.isBlank(nodeType)) {
            return null;
        }

        this.archivePlugin.addNode();
        IEadEntry entry = this.archivePlugin.getSelectedEntry();
        // set the prefered node type for the created node
        for (INodeType nt : this.archivePlugin.getConfig().getConfiguredNodes()) {
            if (nt.getNodeName().equals(nodeType)) {
                entry.setNodeType(nt);
                entry.setGoobiProcessTitle(this.processName);
                addMetadata(entry, row, mappingFields);
                this.archivePlugin.setSelectedEntry(parent);
                return entry.getId();
            }
        }

        // if node type is invalid delete it
        this.archivePlugin.deleteNode();
        this.archivePlugin.setSelectedEntry(parent);
        return null;
    }

}
