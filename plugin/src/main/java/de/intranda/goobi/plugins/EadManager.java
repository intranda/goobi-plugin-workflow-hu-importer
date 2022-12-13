package de.intranda.goobi.plugins;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.goobi.beans.User;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.IMetadataField;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ImportSet;
import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.MappingField;
import de.intranda.goobi.plugins.model.FieldValue;
import de.sub.goobi.helper.Helper;
import io.goobi.workflow.locking.LockingBean;
import lombok.Getter;

public class EadManager {
    private ArchiveManagementAdministrationPlugin archivePlugin;
    private String processName;
    private ImportSet importSet;
    private boolean setNodeId;
    private IEadEntry selectedNode = null;
    @Getter
    private boolean dbStatusOk;

    public EadManager(ImportSet importSet, String processName, String processNamingMode) {
        this.importSet = importSet;
        this.processName = processName;

        // if processNamingMode equals EAD use the UUID of the process as UUID of the new Node
        this.setNodeId = "EAD".equalsIgnoreCase(processNamingMode);

        // find out if archive file is locked currently
        IPlugin ia = PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management");
        this.archivePlugin = (ArchiveManagementAdministrationPlugin) ia;

        User user = Helper.getCurrentUser();
        String username = user != null ? user.getNachVorname() : "-";
        if (!LockingBean.lockObject(importSet.getEadFile(), username)) {
            this.dbStatusOk = false;
            return;
        } else {
            // prepare ArchivePlugin
            this.archivePlugin.getPossibleDatabases();
            this.archivePlugin.setSelectedDatabase(importSet.getEadFile());
            this.archivePlugin.loadSelectedDatabase();
            this.dbStatusOk = checkDB();
        }

        if (this.dbStatusOk) {
            try {
                this.selectedNode = findNode(importSet.getEadNode());
                if (this.selectedNode != null) {
                    archivePlugin.setSelectedEntry(this.selectedNode);
                }
            } catch (NullPointerException ex) {
                this.dbStatusOk = false;
            }
        }

    }

    private boolean checkDB() {
        List<String> possibleDBs = archivePlugin.getPossibleDatabases();
        return !possibleDBs.isEmpty() && StringUtils.isNotBlank(this.archivePlugin.getSelectedDatabase())
                && this.archivePlugin.getSelectedDatabase().equals(importSet.getEadFile());
    }

    private IEadEntry findNode(String eadNode) throws NullPointerException {
        return findNode(archivePlugin.getRootElement(), eadNode);
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
        archivePlugin.addNode();
        IEadEntry entry = archivePlugin.getSelectedEntry();
        // set the prefered node type for the created node
        for (INodeType nt : archivePlugin.getConfiguredNodes()) {
            if (nt.getNodeName().equals(importSet.getEadType())) {
                entry.setNodeType(nt);
            }
        }
        if (setNodeId) {
            entry.setId(processName);
        }
        addMetadata(entry, row, mappingFields);
        entry.setGoobiProcessTitle(entry.getId());

        archivePlugin.createEadDocument();
        return entry.getId();
    }

    public void saveArchiveAndLeave() {
        archivePlugin.createEadDocument();
        archivePlugin.saveArchiveAndLeave();
    }

    public void cancelEdition() {
        archivePlugin.cancelEdition();
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
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getIdentityStatementAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getContextAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getContentAndStructureAreaAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getAccessAndUseAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getAlliedMaterialsAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getNotesAreaList())) {
            return;
        }
        if (addEadMetadata(entry, fieldName, fieldValue, entry.getDescriptionControlAreaList())) {
            return;
        }
    }

    /**
     * iterate through all metadata fields of a specific list
     * 
     * @param entry
     * @param fieldName
     * @param fieldValue
     * @param list
     * @return
     */
    private boolean addEadMetadata(IEadEntry entry, String fieldName, String fieldValue, List<IMetadataField> list) {
        for (IMetadataField field : list) {
            if (field.getName().equals(fieldName)) {
                FieldValue value = new FieldValue(field);
                value.setValue(fieldValue.trim());
                field.setValues(Arrays.asList(value));
                return true;
            }
        }
        return false;
    }

    public String addSubnodeWithMetaData(Row row, List<MappingField> mappingFields) {
        String NodeType = importSet.getEadSubnodeType();
        IEadEntry parent = archivePlugin.getSelectedEntry();
        if (StringUtils.isBlank(NodeType)) {
            return null;
        }

        archivePlugin.addNode();
        IEadEntry entry = archivePlugin.getSelectedEntry();
        // set the prefered node type for the created node
        for (INodeType nt : archivePlugin.getConfiguredNodes()) {
            if (nt.getNodeName().equals(NodeType)) {
                entry.setNodeType(nt);
                entry.setGoobiProcessTitle(processName);
                addMetadata(entry, row, mappingFields);
                archivePlugin.setSelectedEntry(parent);
                return entry.getId();
            }
        }

        // if node type is invalid delete it
        archivePlugin.deleteNode();
        archivePlugin.setSelectedEntry(parent);
        return null;
    }

}
