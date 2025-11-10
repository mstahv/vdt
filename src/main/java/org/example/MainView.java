package org.example;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.components.TreeTable;
import org.vaadin.firitin.components.button.VButton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Route
public class MainView extends VerticalLayout {

    private final MavenDependencyService dependencyService;
    private final TreeTable<DependencyNode> treeTable;

    public MainView(MavenDependencyService dependencyService) {
        this.dependencyService = dependencyService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(
                new Header(),
                new CoordinatesInputSection(),
                new PomUploadSection(),
                treeTable = new DependencyTreeTable()
        );
        setFlexGrow(1, treeTable);
    }

    class Header extends VerticalLayout {
        Header() {
            setSpacing(false);
            setPadding(false);

            H1 title = new H1("Maven Dependency Analyzer");
            title.getStyle().setMarginBottom("0");

            Paragraph description = new Paragraph(
                    "Analyze Maven dependencies by uploading a pom.xml file or entering Maven coordinates."
            );
            description.getStyle()
                    .setColor("var(--lumo-secondary-text-color)")
                    .setMarginTop("0.5em");

            add(title, description);
        }
    }

    class CoordinatesInputSection extends HorizontalLayout {
        CoordinatesInputSection() {
            setAlignItems(Alignment.END);
            setWidthFull();
            getStyle()
                    .setPadding("var(--lumo-space-m)")
                    .setBackground("var(--lumo-contrast-5pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");

            TextField coordinatesField = new TextField("Maven Coordinates");
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
                add(new VButton("Analyze this project", e -> analyzePomFile(pomContent)));
            } catch (IOException e) {
                // No project pom.xml available
            }
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

    class DependencyTreeTable extends TreeTable<DependencyNode> {
        DependencyTreeTable() {
            setSizeFull();
            addHierarchyColumn(DependencyNode::getCoordinates).setHeader("Dependency");
            addColumn(DependencyNode::getScope).setHeader("Scope").setAutoWidth(true).setFlexGrow(0);
            addColumn(node -> node.isOptional() ? "Yes" : "No").setHeader("Optional").setWidth("100px").setFlexGrow(0);
            getColumns().forEach(c -> c.setResizable(true));
            setVisible(false);
            getStyle()
                    .setBorder("1px solid var(--lumo-contrast-10pct)")
                    .setBorderRadius("var(--lumo-border-radius-m)");
        }
    }

    private void analyzeDependencies(String coordinates) {
        try {
            DependencyNode root = dependencyService.resolveDependencies(coordinates);
            displayDependencyTree(root);
            showSuccess("Dependencies resolved successfully!");
        } catch (Exception e) {
            showError("Failed to resolve dependencies: " + e.getMessage());
        }
    }

    private void analyzePomFile(String pomContent) {
        try {
            DependencyNode root = dependencyService.resolveDependenciesFromPom(pomContent);
            displayDependencyTree(root);
            showSuccess("POM file analyzed successfully!");
        } catch (Exception e) {
            showError("Failed to analyze POM file: " + e.getMessage());
        }
    }

    private void displayDependencyTree(DependencyNode root) {
        treeTable.setRootItems(List.of(root), DependencyNode::getChildren);
        treeTable.setVisible(true);
    }

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 3000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
