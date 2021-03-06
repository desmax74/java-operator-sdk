package io.javaoperatorsdk.operator.config.runtime;

import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.ControllerUtils;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import java.util.Set;

public class AnnotationConfiguration<R extends CustomResource>
    implements ControllerConfiguration<R> {

  private final ResourceController<R> controller;
  private final Controller annotation;

  public AnnotationConfiguration(ResourceController<R> controller) {
    this.controller = controller;
    this.annotation = controller.getClass().getAnnotation(Controller.class);
  }

  @Override
  public String getName() {
    return ControllerUtils.getNameFor(controller);
  }

  @Override
  public String getCRDName() {
    return CustomResource.getCRDName(getCustomResourceClass());
  }

  @Override
  public String getFinalizer() {
    final String annotationFinalizerName = annotation.finalizerName();
    if (!Controller.NULL.equals(annotationFinalizerName)) {
      return annotationFinalizerName;
    }
    return ControllerUtils.getDefaultFinalizerName(getCRDName());
  }

  @Override
  public boolean isGenerationAware() {
    return annotation.generationAwareEventProcessing();
  }

  @Override
  public Class<R> getCustomResourceClass() {
    return RuntimeControllerMetadata.getCustomResourceClass(controller);
  }

  @Override
  public Set<String> getNamespaces() {
    return Set.of(annotation.namespaces());
  }

  @Override
  public String getAssociatedControllerClassName() {
    return controller.getClass().getCanonicalName();
  }
}
