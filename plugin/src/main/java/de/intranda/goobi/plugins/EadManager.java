package de.intranda.goobi.plugins;

import org.goobi.beans.User;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ImportSet;
import de.sub.goobi.helper.Helper;
import io.goobi.workflow.locking.LockingBean;

public class EadManager {
    private ArchiveManagementAdministrationPlugin archivePlugin;
    public EadManager(ImportSet importSet) {
        // find out if archive file is locked currently
        IPlugin ia = PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management");
        this.archivePlugin = (ArchiveManagementAdministrationPlugin) ia;

        User user = Helper.getCurrentUser();
        String username = user != null ? user.getNachVorname() : "-";
        if (!LockingBean.lockObject(importSet.getEadFile(), username)) {
            Helper.setFehlerMeldung("plugin_administration_archive_databaseLocked");
            return;
        }
        
        //prepare ArchivePlugin
        this.archivePlugin.getPossibleDatabases();
        this.archivePlugin.setSelectedDatabase(importSet.getEadFile());
        this.archivePlugin.loadSelectedDatabase();
    }

}
