package de.intranda.goobi.plugins;

import java.util.HashMap;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ImportSet;
import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.ProcessDescription;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import lombok.Getter;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.fileformats.mets.MetsMods;

public class DocumentManager {
    @Getter
    private Process process;
    @Getter
    private Prefs prefs;
    private HuImporterWorkflowPlugin plugin;

    public DocumentManager(ProcessDescription processDescription, ImportSet importSet, HuImporterWorkflowPlugin plugin) throws ProcessCreationException {
        this.plugin=plugin;
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

            // add some properties
            bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
            bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());

            String projectName = importSet.getProject();
            if (!StringUtils.isBlank(projectName)) {
                try {
                    ProjectManager.getProjectByName(projectName);
                } catch (DAOException e) {
                    plugin.updateLog("A Project with the name: " + projectName + " does not exist. Please update the configuration or create the Project.",3);
                }
            }
            this.process = process;

        } catch (PreferencesException | TypeNotAllowedForParentException ex) {
            throw new ProcessCreationException(ex);
        }
    }

}
