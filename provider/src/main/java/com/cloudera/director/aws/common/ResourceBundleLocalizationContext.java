//  (c) Copyright 2015 Cloudera, Inc.

package com.cloudera.director.aws.common;

import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.cloudera.director.spi.v1.model.util.AbstractLocalizationContext;
import com.google.common.annotations.VisibleForTesting;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Localization context that uses resource bundles to perform localization.
 */
public class ResourceBundleLocalizationContext extends AbstractLocalizationContext {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceBundleLocalizationContext.class);

  /**
   * Resolver for locale-specific resource bundles.
   */
  public interface ResourceBundleResolver {

    /**
     * The default resource bundle resolver.
     */
    ResourceBundleResolver DEFAULT = new ResourceBundleResolver() {
      public ResourceBundle getBundle(String baseName, Locale locale) {
        try {
          return ResourceBundle.getBundle("com.cloudera.director.aws." + baseName, locale);
        } catch (MissingResourceException e) {
          LOG.warn("Could not load resource bundle '{}' for locale {}", baseName, locale);
          return null;
        }
      }
    };

    /**
     * Returns the specified resource bundle for the specified locale, or {@code null} if there
     * is no such resource bundle.
     *
     * @param baseName the base name of the resource bundle
     * @param locale   the locale
     * @return the specified resource bundle for the specified locale, or {@code null} if there
     * is no such resource bundle
     */
    ResourceBundle getBundle(String baseName, Locale locale);
  }

  @VisibleForTesting
  final static String BUNDLE_BASE_NAME = "Messages";

  /**
   * The default resource bundle localization factory.
   */
  public static final Factory FACTORY =
      new ResourceBundleLocalizationContextFactory(ResourceBundleResolver.DEFAULT);

  /**
   * Default localization context factory implementation.
   */
  public static class ResourceBundleLocalizationContextFactory implements Factory {

    /**
     * The resource bundle resolver.
     */
    private final ResourceBundleResolver resourceBundleResolver;

    /**
     * Creates a resource bundle localization context factory with the specified parameters.
     *
     * @param resourceBundleResolver the resource bundle resolver
     */
    public ResourceBundleLocalizationContextFactory(ResourceBundleResolver resourceBundleResolver) {
      this.resourceBundleResolver = resourceBundleResolver;
    }

    @Override
    public LocalizationContext createRootLocalizationContext(Locale locale) {
      return new ResourceBundleLocalizationContext(
          locale, "", resourceBundleResolver.getBundle(BUNDLE_BASE_NAME, locale));
    }
  }

  /**
   * The backing resource bundle.
   */
  private final ResourceBundle resourceBundle;

  /**
   * Creates a resource bundle localization context with the specified parameters.
   *
   * @param locale         the locale
   * @param keyPrefix      the key prefix of the context, which can be used for property namespacing
   * @param resourceBundle the backing resource bundle
   */
  public ResourceBundleLocalizationContext(Locale locale, String keyPrefix,
      ResourceBundle resourceBundle) {
    super(locale, keyPrefix);
    this.resourceBundle = resourceBundle;
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Override
  public String localize(String defaultValue, String... keyComponents) {
    String result = defaultValue;
    if (resourceBundle != null) {
      int len = keyComponents.length;
      if (len != 0) {
        String key = buildKey(getKeyPrefix(), buildKey(keyComponents));
        try {
          result = resourceBundle.getString(key);
        } catch (MissingResourceException ignore) {
        }
      }
    }
    return result;
  }
}
