package com.codepilot1c.ui.preferences;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;

import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.ui.internal.Messages;

public class QaProjectPropertyPage extends PropertyPage {

    private static final String DEFAULT_CONFIG_RELATIVE_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$

    private Text configPathText;
    private Button useEdtRuntimeButton;
    private Button useTestManagerButton;
    private Text projectNameText;
    private Text timeoutSecondsText;
    private Text epfPathText;
    private Text paramsTemplateText;
    private Text stepsCatalogText;
    private Text featuresDirText;
    private Text stepsDirText;
    private Text resultsDirText;
    private Combo unknownStepsModeCombo;
    private Button junitReportButton;
    private Button screenshotsOnFailureButton;
    private Button closeTestClientButton;
    private Button quietInstallButton;
    private Button showMainFormButton;

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));

        IProject project = getProject();
        if (project == null) {
            Label label = new Label(container, SWT.WRAP);
            label.setText(Messages.QaProjectPropertyPage_NoProject);
            return container;
        }

        createInfoSection(container, project);
        createRuntimeSection(container);
        createPathsSection(container);
        createBehaviorSection(container);

        loadConfigIntoControls(project);
        return container;
    }

    @Override
    protected void performDefaults() {
        IProject project = getProject();
        if (project != null) {
            populateControls(QaConfig.defaultConfig(project.getName()), project);
        }
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IProject project = getProject();
        if (project == null) {
            return true;
        }

        try {
            File configFile = resolveConfigFile(project);
            if (configFile == null) {
                throw new IOException(Messages.QaProjectPropertyPage_NoProject);
            }
            QaConfig config = createConfigFromControls(project);
            config.save(configFile);
            return true;
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            MessageDialog.openError(getShell(), Messages.QaProjectPropertyPage_SaveErrorTitle, e.getMessage());
            return false;
        }
    }

    private void createInfoSection(Composite parent, IProject project) {
        Group group = createGroup(parent, Messages.QaProjectPropertyPage_InfoGroup, 1);

        Label projectLabel = new Label(group, SWT.NONE);
        projectLabel.setText(Messages.QaProjectPropertyPage_ProjectLabel + project.getName());

        Composite pathRow = new Composite(group, SWT.NONE);
        pathRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        pathRow.setLayout(new GridLayout(2, false));

        Label label = new Label(pathRow, SWT.NONE);
        label.setText(Messages.QaProjectPropertyPage_ConfigPathLabel);

        configPathText = new Text(pathRow, SWT.BORDER | SWT.READ_ONLY);
        configPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createRuntimeSection(Composite parent) {
        Group group = createGroup(parent, Messages.QaProjectPropertyPage_RuntimeGroup, 3);

        useEdtRuntimeButton = new Button(group, SWT.CHECK);
        useEdtRuntimeButton.setText(Messages.QaProjectPropertyPage_UseEdtRuntimeLabel);
        GridData checkboxSpan = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        useEdtRuntimeButton.setLayoutData(checkboxSpan);

        createLabel(group, Messages.QaProjectPropertyPage_ProjectNameLabel);
        projectNameText = createText(group);
        createSpacer(group);

        useTestManagerButton = new Button(group, SWT.CHECK);
        useTestManagerButton.setText(Messages.QaProjectPropertyPage_UseTestManagerLabel);
        useTestManagerButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        createLabel(group, Messages.QaProjectPropertyPage_TimeoutLabel);
        timeoutSecondsText = createText(group);
        createSpacer(group);

        createLabel(group, Messages.QaProjectPropertyPage_EpfPathLabel);
        epfPathText = createText(group);
        createFileBrowseButton(group, epfPathText, true);

        Label note = new Label(group, SWT.WRAP);
        note.setText(Messages.QaProjectPropertyPage_EpfPathNote);
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        createLabel(group, Messages.QaProjectPropertyPage_ParamsTemplateLabel);
        paramsTemplateText = createText(group);
        createFileBrowseButton(group, paramsTemplateText, false);

        Label templateNote = new Label(group, SWT.WRAP);
        templateNote.setText(Messages.QaProjectPropertyPage_ParamsTemplateNote);
        templateNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    }

    private void createPathsSection(Composite parent) {
        Group group = createGroup(parent, Messages.QaProjectPropertyPage_PathsGroup, 3);

        createLabel(group, Messages.QaProjectPropertyPage_FeaturesDirLabel);
        featuresDirText = createText(group);
        createDirectoryBrowseButton(group, featuresDirText);

        createLabel(group, Messages.QaProjectPropertyPage_StepsDirLabel);
        stepsDirText = createText(group);
        createDirectoryBrowseButton(group, stepsDirText);

        Label stepsDirNote = new Label(group, SWT.WRAP);
        stepsDirNote.setText(Messages.QaProjectPropertyPage_StepsDirNote);
        stepsDirNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        createLabel(group, Messages.QaProjectPropertyPage_StepsCatalogLabel);
        stepsCatalogText = createText(group);
        createFileBrowseButton(group, stepsCatalogText, false);

        Label stepsCatalogNote = new Label(group, SWT.WRAP);
        stepsCatalogNote.setText(Messages.QaProjectPropertyPage_StepsCatalogNote);
        stepsCatalogNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        createLabel(group, Messages.QaProjectPropertyPage_ResultsDirLabel);
        resultsDirText = createText(group);
        createDirectoryBrowseButton(group, resultsDirText);
    }

    private void createBehaviorSection(Composite parent) {
        Group group = createGroup(parent, Messages.QaProjectPropertyPage_BehaviorGroup, 1);

        junitReportButton = createCheckbox(group, Messages.QaProjectPropertyPage_JunitReportLabel);
        screenshotsOnFailureButton = createCheckbox(group, Messages.QaProjectPropertyPage_ScreenshotsLabel);
        closeTestClientButton = createCheckbox(group, Messages.QaProjectPropertyPage_CloseClientLabel);
        quietInstallButton = createCheckbox(group, Messages.QaProjectPropertyPage_QuietInstallLabel);
        showMainFormButton = createCheckbox(group, Messages.QaProjectPropertyPage_ShowMainFormLabel);

        Composite row = new Composite(group, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.setLayout(new GridLayout(2, false));
        createLabel(row, Messages.QaProjectPropertyPage_UnknownStepsModeLabel);
        unknownStepsModeCombo = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY);
        unknownStepsModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        unknownStepsModeCombo.setItems(new String[] {
                "warn", //$NON-NLS-1$
                "off", //$NON-NLS-1$
                "strict" //$NON-NLS-1$
        });
        unknownStepsModeCombo.select(0);

        Label unknownStepsNote = new Label(group, SWT.WRAP);
        unknownStepsNote.setText(Messages.QaProjectPropertyPage_UnknownStepsModeNote);
        unknownStepsNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void loadConfigIntoControls(IProject project) {
        try {
            File configFile = resolveConfigFile(project);
            QaConfig config = configFile.exists() ? QaConfig.load(configFile) : QaConfig.defaultConfig(project.getName());
            populateControls(config, project);
        } catch (IOException e) {
            setErrorMessage(e.getMessage());
            populateControls(QaConfig.defaultConfig(project.getName()), project);
        }
    }

    private void populateControls(QaConfig config, IProject project) {
        QaConfig effective = config == null ? QaConfig.defaultConfig(project.getName()) : config;
        if (effective.edt == null) {
            effective.edt = new QaConfig.Edt();
        }
        if (effective.paths == null) {
            effective.paths = new QaConfig.Paths();
        }
        if (effective.vanessa == null) {
            effective.vanessa = new QaConfig.Vanessa();
        }

        File configFile = resolveConfigFile(project);
        configPathText.setText(configFile == null ? "" : configFile.getAbsolutePath()); //$NON-NLS-1$
        useEdtRuntimeButton.setSelection(Boolean.TRUE.equals(effective.edt.use_runtime));
        useTestManagerButton.setSelection(effective.test_runner == null
                || !Boolean.FALSE.equals(effective.test_runner.use_test_manager));
        projectNameText.setText(safe(effective.edt.project_name, project.getName()));
        timeoutSecondsText.setText(Integer.toString(resolveTimeout(effective)));
        epfPathText.setText(safe(effective.vanessa.epf_path, "")); //$NON-NLS-1$
        paramsTemplateText.setText(safe(effective.vanessa.params_template, "")); //$NON-NLS-1$
        stepsCatalogText.setText(safe(effective.vanessa.steps_catalog, "")); //$NON-NLS-1$
        featuresDirText.setText(safe(effective.paths.features_dir, "tests/features")); //$NON-NLS-1$
        stepsDirText.setText(safe(effective.paths.steps_dir, "tests/steps")); //$NON-NLS-1$
        resultsDirText.setText(safe(effective.paths.results_dir, "tests/qa/results")); //$NON-NLS-1$
        junitReportButton.setSelection(resolveBoolean(effective.vanessa.junit_report_enabled, true));
        screenshotsOnFailureButton.setSelection(resolveBoolean(effective.vanessa.screenshots_on_failure, true));
        closeTestClientButton.setSelection(resolveBoolean(effective.vanessa.close_test_client_after_run, true));
        quietInstallButton.setSelection(Boolean.TRUE.equals(effective.vanessa.quiet_install_ext));
        showMainFormButton.setSelection(Boolean.TRUE.equals(effective.vanessa.show_main_form));
        selectUnknownStepsMode(safe(effective.test_runner.unknown_steps_mode, "warn")); //$NON-NLS-1$
    }

    private QaConfig createConfigFromControls(IProject project) throws IOException {
        File configFile = resolveConfigFile(project);
        QaConfig config = configFile.exists() ? QaConfig.load(configFile) : QaConfig.defaultConfig(project.getName());
        if (config.edt == null) {
            config.edt = new QaConfig.Edt();
        }
        if (config.paths == null) {
            config.paths = new QaConfig.Paths();
        }
        if (config.vanessa == null) {
            config.vanessa = new QaConfig.Vanessa();
        }
        if (config.test_runner == null) {
            config.test_runner = new QaConfig.TestRunner();
        }

        config.edt.use_runtime = Boolean.valueOf(useEdtRuntimeButton.getSelection());
        config.test_runner.use_test_manager = Boolean.valueOf(useTestManagerButton.getSelection());
        config.test_runner.timeout_seconds = Integer.valueOf(parsePositiveInt(timeoutSecondsText.getText(), 300));
        config.edt.project_name = normalize(projectNameText.getText(), project.getName());

        config.vanessa.epf_path = normalize(epfPathText.getText(), null);
        config.vanessa.params_template = normalize(paramsTemplateText.getText(), null);
        config.vanessa.steps_catalog = normalize(stepsCatalogText.getText(), null);
        config.vanessa.junit_report_enabled = Boolean.valueOf(junitReportButton.getSelection());
        config.vanessa.screenshots_on_failure = Boolean.valueOf(screenshotsOnFailureButton.getSelection());
        config.vanessa.close_test_client_after_run = Boolean.valueOf(closeTestClientButton.getSelection());
        config.vanessa.quiet_install_ext = Boolean.valueOf(quietInstallButton.getSelection());
        config.vanessa.show_main_form = Boolean.valueOf(showMainFormButton.getSelection());
        config.test_runner.unknown_steps_mode = normalize(unknownStepsModeCombo.getText(), "warn"); //$NON-NLS-1$

        config.paths.features_dir = normalize(featuresDirText.getText(), "tests/features"); //$NON-NLS-1$
        config.paths.steps_dir = normalize(stepsDirText.getText(), "tests/steps"); //$NON-NLS-1$
        config.paths.results_dir = normalize(resultsDirText.getText(), "tests/qa/results"); //$NON-NLS-1$
        return config;
    }

    private Group createGroup(Composite parent, String title, int columns) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout(columns, false));
        return group;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private Text createText(Composite parent) {
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return text;
    }

    private void createSpacer(Composite parent) {
        Label spacer = new Label(parent, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Button createCheckbox(Composite parent, String text) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return button;
    }

    private void createFileBrowseButton(Composite parent, Text target, boolean epfOnly) {
        Button browse = new Button(parent, SWT.PUSH);
        browse.setText(Messages.QaProjectPropertyPage_BrowseButton);
        browse.addListener(SWT.Selection, event -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
            if (epfOnly) {
                dialog.setFilterExtensions(new String[] { "*.epf", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String current = normalize(target.getText(), null);
            if (current != null) {
                dialog.setFileName(current);
            }
            String selected = dialog.open();
            if (selected != null) {
                target.setText(selected);
            }
        });
    }

    private void createDirectoryBrowseButton(Composite parent, Text target) {
        Button browse = new Button(parent, SWT.PUSH);
        browse.setText(Messages.QaProjectPropertyPage_BrowseButton);
        browse.addListener(SWT.Selection, event -> {
            DirectoryDialog dialog = new DirectoryDialog(getShell());
            String current = normalize(target.getText(), null);
            if (current != null) {
                dialog.setFilterPath(current);
            }
            String selected = dialog.open();
            if (selected != null) {
                target.setText(selected);
            }
        });
    }

    private File resolveConfigFile(IProject project) {
        if (project == null || project.getLocation() == null) {
            return null;
        }
        return project.getLocation().append(DEFAULT_CONFIG_RELATIVE_PATH).toFile();
    }

    private IProject getProject() {
        IAdaptable element = getElement();
        if (element == null) {
            return null;
        }
        return element.getAdapter(IProject.class);
    }

    private static boolean resolveBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value.booleanValue();
    }

    private static String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int resolveTimeout(QaConfig config) {
        if (config != null && config.test_runner != null && config.test_runner.timeout_seconds != null
                && config.test_runner.timeout_seconds.intValue() > 0) {
            return config.test_runner.timeout_seconds.intValue();
        }
        return 300;
    }

    private static int parsePositiveInt(String value, int fallback) {
        String normalized = normalize(value, null);
        if (normalized == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void selectUnknownStepsMode(String mode) {
        String normalized = normalize(mode, "warn"); //$NON-NLS-1$
        int index = switch (normalized) {
            case "off" -> 1; //$NON-NLS-1$
            case "strict" -> 2; //$NON-NLS-1$
            default -> 0;
        };
        unknownStepsModeCombo.select(index);
    }
}
