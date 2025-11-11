package org.example;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.components.TreeTable;
import org.vaadin.firitin.components.button.VButton;
import org.vaadin.firitin.util.VStyleUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Route
public class MainView extends VerticalLayout {

    private final MavenDependencyService dependencyService;
    private final TreeTable<DependencyNode> treeTable;
    private final CoordinatesInputSection coordinatesInputSection;
    private final PomUploadSection pomUploadSection;
    private final FilterBar filterBar;
    private final SummarySection summarySection;
    private final Header header;
    private DependencyNode rootNode;
    private String lastAnalyzedCoordinates = "";

    public MainView(MavenDependencyService dependencyService) {
        this.dependencyService = dependencyService;

        VStyleUtil.inject("""
                vaadin-checkbox label {
                    white-space: nowrap;
                }
                """);

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        filterBar = new FilterBar();
        filterBar.setVisible(false);

        summarySection = new SummarySection();
        summarySection.setVisible(false);

        header = new Header();

        add(
                header,
                coordinatesInputSection = new CoordinatesInputSection(),
                pomUploadSection = new PomUploadSection(),
                filterBar,
                treeTable = new DependencyTreeTable(),
                summarySection
        );
        setFlexGrow(1, treeTable);
    }

    private void analyzeDependencies(String coordinates) {
        try {
            lastAnalyzedCoordinates = coordinates;
            DependencyNode root = dependencyService.resolveDependencies(coordinates);
            String projectInfo = coordinates;
            displayDependencyTree(root, projectInfo);
            showSuccess("Dependencies resolved successfully!");
        } catch (Exception e) {
            showError("Failed to resolve dependencies: " + e.getMessage());
        }
    }

    private void analyzePomFile(String pomContent) {
        try {
            DependencyNode root = dependencyService.resolveDependenciesFromPom(pomContent);
            // Build project info string
            String projectInfo = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();
            lastAnalyzedCoordinates = projectInfo;
            displayDependencyTree(root, projectInfo);
            showSuccess("POM file analyzed successfully!");
        } catch (Exception e) {
            showError("Failed to analyze POM file: " + e.getMessage());
        }
    }

    private void displayDependencyTree(DependencyNode root, String projectInfo) {
        this.rootNode = root;

        // Hide input sections
        coordinatesInputSection.setVisible(false);
        pomUploadSection.setVisible(false);

        // Update header to analysis mode
        header.showAnalysisMode(projectInfo);

        // Show filter bar and summary
        filterBar.setVisible(true);
        summarySection.setVisible(true);
        summarySection.updateSummary(root);

        // Display the tree
        applyFilters();
        treeTable.setVisible(true);
    }

    private void resetToInputMode() {
        // Hide analysis components
        treeTable.setVisible(false);
        filterBar.setVisible(false);
        summarySection.setVisible(false);

        // Show input sections
        coordinatesInputSection.setVisible(true);
        coordinatesInputSection.setCoordinates(lastAnalyzedCoordinates);
        pomUploadSection.setVisible(true);

        // Update header to input mode
        header.showInputMode();

        // Clear state
        rootNode = null;
    }

    private void applyFilters() {
        if (rootNode == null) {
            return;
        }

        String searchText = filterBar.searchField.getValue();
        String scopeValue = filterBar.scopeFilter.getValue();
        boolean showOptionals = filterBar.showOptionalsFilter.getValue();
        boolean showOmitted = filterBar.showOmittedFilter.getValue();

        // Update search highlighting
        ((DependencyTreeTable) treeTable).updateSearchHighlight(searchText);

        List<DependencyNode> filteredRoots = filterDependencyTree(
                List.of(rootNode),
                searchText,
                scopeValue,
                showOptionals,
                showOmitted
        );

        treeTable.setRootItems(filteredRoots, node -> filterDependencyTree(
                node.getChildren(),
                searchText,
                scopeValue,
                showOptionals,
                showOmitted
        ));
    }

    private List<DependencyNode> filterDependencyTree(List<DependencyNode> nodes,
                                                      String searchText,
                                                      String scope,
                                                      boolean showOptionals,
                                                      boolean showOmitted) {
        if (nodes == null) {
            return new ArrayList<>();
        }

        return nodes.stream()
                .filter(node -> matchesFilters(node, searchText, scope, showOptionals, showOmitted) ||
                        hasMatchingChildren(node, searchText, scope, showOptionals, showOmitted))
                .collect(Collectors.toList());
    }

    private boolean matchesFilters(DependencyNode node, String searchText, String scope, boolean showOptionals, boolean showOmitted) {
        // Text filter
        if (searchText != null && !searchText.trim().isEmpty()) {
            String lowerSearch = searchText.toLowerCase();
            if (!node.getCoordinates().toLowerCase().contains(lowerSearch)) {
                return false;
            }
        }

        // Scope filter
        if (!"All".equals(scope) && scope != null) {
            if (!scope.equals(node.getScope())) {
                return false;
            }
        }

        // Optional filter - hide optional dependencies by default
        if (!showOptionals && node.isOptional()) {
            return false;
        }

        // Omitted filter - hide omitted dependencies by default
        if (!showOmitted && node.isOmitted()) {
            return false;
        }

        return true;
    }

    private boolean hasMatchingChildren(DependencyNode node, String searchText, String scope, boolean showOptionals, boolean showOmitted) {
        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            return false;
        }

        return node.getChildren().stream()
                .anyMatch(child -> matchesFilters(child, searchText, scope, showOptionals, showOmitted) ||
                        hasMatchingChildren(child, searchText, scope, showOptionals, showOmitted));
    }

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 3000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    class Header extends HorizontalLayout {
        private final H1 title;
        private final Paragraph description;
        private final Paragraph projectInfo;
        private final Button newAnalysisButton;

        Header() {
            setAlignItems(Alignment.BASELINE);
            setWidthFull();
            setPadding(false);

            title = new H1("Maven Dependency Analyzer");
            title.getStyle().setMargin("0");

            description = new Paragraph(
                    "Analyze Maven dependencies by uploading a pom.xml file or entering Maven coordinates."
            );

            projectInfo = new Paragraph();
            projectInfo.setVisible(false);

            newAnalysisButton = new VButton(VaadinIcon.REFRESH, "New Analysis...", e -> resetToInputMode());
            newAnalysisButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
            newAnalysisButton.setVisible(false);

            add(title, description, projectInfo, newAnalysisButton);
            setFlexGrow(1, description);
            setFlexGrow(1, projectInfo);
        }

        void showInputMode() {
            description.setVisible(true);
            projectInfo.setVisible(false);
            newAnalysisButton.setVisible(false);
        }

        void showAnalysisMode(String projectName) {
            description.setVisible(false);
            projectInfo.setText(projectName);
            projectInfo.setVisible(true);
            newAnalysisButton.setVisible(true);
        }
    }

    class CoordinatesInputSection extends HorizontalLayout {
        private final TextField coordinatesField;

        CoordinatesInputSection() {
            setAlignItems(Alignment.END);
            setWidthFull();
            getStyle()
                    .setPadding("var(--lumo-space-m)")
                    .setBackground("var(--lumo-contrast-5pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            coordinatesField = new TextField("Maven Coordinates");
            coordinatesField.setPlaceholder("e.g., org.springframework.boot:spring-boot-starter-web:3.2.0");
            coordinatesField.setWidthFull();
            coordinatesField.getStyle().setMaxWidth("600px");

            Button analyzeButton = new Button("Analyze", event -> {
                String coordinates = coordinatesField.getValue();
                if (coordinates != null && !coordinates.trim().isEmpty()) {
                    analyzeDependencies(coordinates);
                }
            });
            analyzeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            add(coordinatesField, analyzeButton);

            try {
                String pomContent = Files.readString(Path.of("./pom.xml"));
                add(new VButton("Analyze this project (example)", e -> analyzePomFile(pomContent)));
            } catch (IOException e) {
                // No project pom.xml available, probably a deployed version.
            }
        }

        void setCoordinates(String coordinates) {
            coordinatesField.setValue(coordinates != null ? coordinates : "");
        }
    }

    class PomUploadSection extends Upload {
        PomUploadSection() {
            MemoryBuffer buffer = new MemoryBuffer();
            setReceiver(buffer);
            setAcceptedFileTypes(".xml", "text/xml", "application/xml");
            setMaxFiles(1);
            setMaxFileSize(5 * 1024 * 1024); // 5MB
            setWidthFull();
            getStyle()
                    .setPadding("var(--lumo-space-m)")
                    .setBackground("var(--lumo-contrast-5pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            addSucceededListener(event -> {
                try {
                    InputStream inputStream = buffer.getInputStream();
                    String pomContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    analyzePomFile(pomContent);
                } catch (Exception e) {
                    showError("Failed to read file: " + e.getMessage());
                }
            });
        }
    }

    class FilterBar extends HorizontalLayout {
        final SearchField searchField;
        final ScopeFilter scopeFilter;
        final ShowOptionalsCheckbox showOptionalsFilter;
        final ShowOmittedCheckbox showOmittedFilter;
        final ShowSizesCheckbox showSizesFilter;

        FilterBar() {
            setAlignItems(Alignment.BASELINE);
            setWidthFull();
            getStyle()
                    .setPadding("var(--lumo-space-s)")
                    .setBackground("var(--lumo-contrast-5pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            searchField = new SearchField();
            scopeFilter = new ScopeFilter();
            showOptionalsFilter = new ShowOptionalsCheckbox();
            showOmittedFilter = new ShowOmittedCheckbox();
            showSizesFilter = new ShowSizesCheckbox();

            var resetButton = new VButton("Reset", e -> {
                searchField.clear();
                scopeFilter.setValue("All");
                showOptionalsFilter.setValue(false);
                showOmittedFilter.setValue(false);
                showSizesFilter.setValue(false);
            });
            resetButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

            add(searchField, scopeFilter, showOptionalsFilter, showOmittedFilter, showSizesFilter, resetButton);
            setFlexGrow(1, searchField);
        }

        class SearchField extends TextField {
            SearchField() {
                setPlaceholder("Filter dependencies...");
                setPrefixComponent(new com.vaadin.flow.component.icon.Icon("vaadin", "search"));
                setValueChangeMode(ValueChangeMode.LAZY);
                addValueChangeListener(e -> applyFilters());
                setWidthFull();
            }
        }

        class ScopeFilter extends ComboBox<String> {
            ScopeFilter() {
                setItems("All scopes", "compile", "test", "runtime", "provided", "system");
                setValue("All");
                addValueChangeListener(e -> applyFilters());
                setWidth("150px");
            }
        }

        class ShowOptionalsCheckbox extends Checkbox {
            ShowOptionalsCheckbox() {
                super("Show optionals");
                setValue(false);
                addValueChangeListener(e -> {
                    ((DependencyTreeTable) treeTable).setOptionalColumnVisible(e.getValue());
                    applyFilters();
                });
            }
        }

        class ShowOmittedCheckbox extends Checkbox {
            ShowOmittedCheckbox() {
                super("Show omitted");
                setValue(false);
                addValueChangeListener(e -> {
                    ((DependencyTreeTable) treeTable).setOmittedColumnVisible(e.getValue());
                    applyFilters();
                });
            }
        }

        class ShowSizesCheckbox extends Checkbox {
            ShowSizesCheckbox() {
                super("Show sizes");
                setValue(false);
                addValueChangeListener(e -> {
                    boolean show = e.getValue();
                    ((DependencyTreeTable) treeTable).setSizeColumnVisible(show);
                    summarySection.setShowSizes(show);
                    if (show) {
                        summarySection.updateSummary(rootNode);
                    }
                });
            }
        }
    }

    class SummarySection extends HorizontalLayout {
        private final Paragraph summaryText;
        private boolean showSizes = false;

        SummarySection() {
            setWidthFull();
            getStyle()
                    .setPadding("var(--lumo-space-s)")
                    .setBackground("var(--lumo-contrast-5pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            summaryText = new Paragraph();
            summaryText.getStyle()
                    .setMargin("0")
                    .setFontSize("var(--lumo-font-size-s)")
                    .setColor("var(--lumo-secondary-text-color)");

            add(summaryText);
        }

        void updateSummary(DependencyNode root) {
            int totalDeps = countAllDependencies(root);
            int compileDeps = countByScope(root, "compile");
            int runtimeDeps = countByScope(root, "runtime");
            int testDeps = countByScope(root, "test");
            int providedDeps = countByScope(root, "provided");
            int systemDeps = countByScope(root, "system");
            int optionalDeps = countOptionalDependencies(root);

            StringBuilder summary = new StringBuilder();
            summary.append("Total: ").append(totalDeps).append(" dependencies");

            if (showSizes) {
                long compileSize = calculateSizeByScope(root, "compile");
                long runtimeSize = calculateSizeByScope(root, "runtime");
                long testSize = calculateSizeByScope(root, "test");

                summary.append(" | Compile: ").append(compileDeps)
                        .append(" (").append(MavenDependencyService.formatSize(compileSize)).append(")");
                summary.append(" | Runtime: ").append(runtimeDeps)
                        .append(" (").append(MavenDependencyService.formatSize(runtimeSize)).append(")");
                summary.append(" | Test: ").append(testDeps)
                        .append(" (").append(MavenDependencyService.formatSize(testSize)).append(")");

                if (providedDeps > 0) {
                    long providedSize = calculateSizeByScope(root, "provided");
                    summary.append(" | Provided: ").append(providedDeps)
                            .append(" (").append(MavenDependencyService.formatSize(providedSize)).append(")");
                }
                if (systemDeps > 0) {
                    long systemSize = calculateSizeByScope(root, "system");
                    summary.append(" | System: ").append(systemDeps)
                            .append(" (").append(MavenDependencyService.formatSize(systemSize)).append(")");
                }
            } else {
                summary.append(" | Compile: ").append(compileDeps);
                summary.append(" | Runtime: ").append(runtimeDeps);
                summary.append(" | Test: ").append(testDeps);

                if (providedDeps > 0) {
                    summary.append(" | Provided: ").append(providedDeps);
                }
                if (systemDeps > 0) {
                    summary.append(" | System: ").append(systemDeps);
                }
            }

            if (optionalDeps > 0) {
                summary.append(" | Optional: ").append(optionalDeps);
            }

            // Add total size information if enabled
            if (showSizes) {
                long totalSize = dependencyService.calculateTotalSize(root);
                summary.append(" | Total size: ").append(MavenDependencyService.formatSize(totalSize));
            }

            summaryText.setText(summary.toString());
        }

        void setShowSizes(boolean show) {
            this.showSizes = show;
        }

        private int countAllDependencies(DependencyNode node) {
            int count = 0;
            if (node.getChildren() != null) {
                for (DependencyNode child : node.getChildren()) {
                    count++; // Count this node
                    count += countAllDependencies(child); // Count children recursively
                }
            }
            return count;
        }

        private int countByScope(DependencyNode node, String scope) {
            int count = 0;
            if (node.getChildren() != null) {
                for (DependencyNode child : node.getChildren()) {
                    if (scope.equals(child.getScope())) {
                        count++;
                    }
                    count += countByScope(child, scope);
                }
            }
            return count;
        }

        private int countOptionalDependencies(DependencyNode node) {
            int count = 0;
            if (node.getChildren() != null) {
                for (DependencyNode child : node.getChildren()) {
                    if (child.isOptional()) {
                        count++;
                    }
                    count += countOptionalDependencies(child);
                }
            }
            return count;
        }

        private long calculateSizeByScope(DependencyNode node, String scope) {
            long size = 0;
            if (node.getChildren() != null) {
                for (DependencyNode child : node.getChildren()) {
                    // Skip omitted dependencies
                    if (child.isOmitted()) {
                        continue;
                    }

                    // Add size if scope matches
                    if (scope.equals(child.getScope())) {
                        size += dependencyService.getArtifactSize(child.getGroupId(), child.getArtifactId(), child.getVersion());
                    }

                    // Recursively process children
                    size += calculateSizeByScope(child, scope);
                }
            }
            return size;
        }
    }

    class DependencyTreeTable extends TreeTable<DependencyNode> {
        private final com.vaadin.flow.component.grid.Grid.Column<DependencyNode> optionalColumn;
        private final com.vaadin.flow.component.grid.Grid.Column<DependencyNode> omittedColumn;
        private final com.vaadin.flow.component.grid.Grid.Column<DependencyNode> sizeColumn;
        private String currentSearchText = "";

        DependencyTreeTable() {
            setSizeFull();
            var hierarchyColumn = addHierarchyComponentColumn(node -> {
                Div div = new Div();
                div.setText(node.getCoordinates());
                div.getStyle().set("text-overflow", "ellipsis")
                        .setOverflow(Style.Overflow.HIDDEN)
                        .setWhiteSpace(Style.WhiteSpace.NOWRAP);

                // Build tooltip content with markdown
                StringBuilder tooltipContent = new StringBuilder();
                tooltipContent.append("**").append(node.getCoordinates()).append("**\n\n");

                if (node.getParent() != null && node.getParent().getParent() != null) {
                    // Parent exists and is not the root node
                    tooltipContent.append("_Brought in by:_\n");
                    tooltipContent.append("`").append(node.getParent().getCoordinates()).append("`");
                }

                Tooltip.forComponent(div).setMarkdown(tooltipContent.toString());

                return div;
            });

            addColumn(DependencyNode::getScope).setHeader("Scope").setAutoWidth(true).setFlexGrow(0);
            sizeColumn = addColumn(node -> {
                long directSize = dependencyService.getArtifactSize(node.getGroupId(), node.getArtifactId(), node.getVersion());
                long totalSize = dependencyService.calculateTotalSize(node);
                if (directSize == 0 && totalSize == 0) {
                    return "-";
                }
                String direct = MavenDependencyService.formatSize(directSize);
                String total = MavenDependencyService.formatSize(totalSize);
                return direct.equals(total) ? direct : direct + " (" + total + ")";
            }).setHeader("Size (Total)").setWidth("150px").setFlexGrow(0);
            sizeColumn.setVisible(false); // Hidden by default
            optionalColumn = addColumn(node -> node.isOptional() ? "Yes" : "").setHeader("Optional").setWidth("100px").setFlexGrow(0);
            optionalColumn.setVisible(false); // Hidden by default
            omittedColumn = addColumn(node -> node.getOmittedReason() != null ? node.getOmittedReason() : "").setHeader("Omitted").setAutoWidth(true);
            omittedColumn.setVisible(false); // Hidden by default
            addColumn(node -> node.getNotes() != null ? node.getNotes() : "").setHeader("Notes").setAutoWidth(true);
            getColumns().forEach(c -> c.setResizable(true));
            setVisible(false);

            getStyle()
                    .setBorder("1px solid var(--lumo-contrast-10pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            // Use withRowStyler to highlight matching rows and color by scope
            withRowStyler((node, style) -> {
                // Color based on omitted status (highest priority)
                if (node.isOmitted()) {
                    style.setColor("gold");
                } else {
                    // Color based on scope and optional status
                    String scope = node.getScope();
                    boolean isOptional = node.isOptional();

                    if (isOptional) {
                        // Optional dependencies are gray regardless of scope
                        style.setColor("var(--lumo-disabled-text-color)");
                    } else if ("test".equals(scope)) {
                        style.setColor("var(--lumo-warning-text-color)");
                    } else if (scope != null && !"compile".equals(scope) && !"runtime".equals(scope)) {
                        // All other scopes (provided, system, etc.) get gray color
                        style.setColor("var(--lumo-disabled-text-color)");
                    }
                }

                // Search highlighting (overrides background)
                if (currentSearchText != null && !currentSearchText.trim().isEmpty()) {
                    String lowerSearch = currentSearchText.toLowerCase();
                    if (node.getCoordinates().toLowerCase().contains(lowerSearch)) {
                        style.setBackground("var(--lumo-primary-color-10pct)");
                        style.setFontWeight("500");
                    }
                }
            });
        }

        void updateSearchHighlight(String searchText) {
            this.currentSearchText = searchText;
            getDataProvider().refreshAll();
        }

        void setOptionalColumnVisible(boolean visible) {
            optionalColumn.setVisible(visible);
        }

        void setOmittedColumnVisible(boolean visible) {
            omittedColumn.setVisible(visible);
        }

        void setSizeColumnVisible(boolean visible) {
            sizeColumn.setVisible(visible);
        }
    }
}
