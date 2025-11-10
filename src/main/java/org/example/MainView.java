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

        // Header
        H1 title = new H1("Maven Dependency Analyzer");
        title.getStyle().set("margin-bottom", "0");

        Paragraph description = new Paragraph(
                "Analyze Maven dependencies by uploading a pom.xml file or entering Maven coordinates."
        );
        description.getStyle().set("color", "var(--lumo-secondary-text-color)");
        description.getStyle().set("margin-top", "0.5em");

        // Maven coordinates input section
        TextField coordinatesField = new TextField("Maven Coordinates");
        coordinatesField.setPlaceholder("e.g., org.springframework.boot:spring-boot-starter-web:3.2.0");
        coordinatesField.setWidthFull();
        coordinatesField.getStyle().set("max-width", "600px");

        Button analyzeButton = new Button("Analyze", event -> {
            String coordinates = coordinatesField.getValue();
            if (coordinates != null && !coordinates.trim().isEmpty()) {
                analyzeDependencies(coordinates);
            }
        });
        analyzeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);


        HorizontalLayout coordinatesLayout = new HorizontalLayout(coordinatesField, analyzeButton);
        try {
            String pomcontent = Files.readString(Path.of("./pom.xml"));
            Button testThis = new VButton("Analyze this project" , e-> {
                analyzePomFile(pomcontent);
            });
            coordinatesLayout.add(testThis);
        } catch (IOException e) {
        }
        coordinatesLayout.setAlignItems(Alignment.END);
        coordinatesLayout.setWidthFull();
        coordinatesLayout.getStyle()
                .set("padding", "var(--lumo-space-m)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");

        // POM file upload section
        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(".xml", "text/xml", "application/xml");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(5 * 1024 * 1024); // 5MB
        upload.setWidthFull();
        upload.getStyle()
                .set("padding", "var(--lumo-space-m)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");

        upload.addSucceededListener(event -> {
            try {
                InputStream inputStream = buffer.getInputStream();
                String pomContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                analyzePomFile(pomContent);
            } catch (Exception e) {
                showError("Failed to read file: " + e.getMessage());
            }
        });

        // TreeTable for displaying dependencies
        treeTable = new TreeTable<>();
        treeTable.setSizeFull();

        treeTable.addHierarchyColumn(DependencyNode::getCoordinates).setHeader("Dependency");
        treeTable.addColumn(DependencyNode::getScope).setHeader("Scope").setAutoWidth(true).setFlexGrow(0);
        treeTable.addColumn(node -> node.isOptional() ? "Yes" : "No").setHeader("Optional").setWidth("100px").setFlexGrow(0);
        treeTable.getColumns().forEach(c -> c.setResizable(true));
        treeTable.setVisible(false);
        treeTable.getStyle()
                .setBorder("1px solid var(--lumo-contrast-10pct)")
                .setBorderRadius("var(--lumo-border-radius-m)");

        // Layout
        add(title, description, coordinatesLayout, upload, treeTable);
        setFlexGrow(1, treeTable);
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
