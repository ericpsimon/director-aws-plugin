// (c) Copyright 2015 Cloudera, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.director.aws.rds;

import static com.cloudera.director.aws.rds.RDSEngine.getSupportedEngineNamesByDatabaseType;
import static com.cloudera.director.aws.rds.RDSProvider.RDSProviderConfigurationPropertyToken.REGION;
import static com.cloudera.director.aws.rds.RDSProvider.RDSProviderConfigurationPropertyToken.REGION_ENDPOINT;
import static com.google.common.base.Preconditions.checkNotNull;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.rds.AmazonRDSClient;
import com.amazonaws.services.rds.model.CreateDBInstanceRequest;
import com.amazonaws.services.rds.model.DBInstance;
import com.amazonaws.services.rds.model.DBInstanceNotFoundException;
import com.amazonaws.services.rds.model.DeleteDBInstanceRequest;
import com.amazonaws.services.rds.model.DescribeDBInstancesRequest;
import com.amazonaws.services.rds.model.DescribeDBInstancesResult;
import com.amazonaws.services.rds.model.Tag;
import com.cloudera.director.aws.AWSExceptions;
import com.cloudera.director.aws.InstanceTags;
import com.cloudera.director.aws.Tags;
import com.cloudera.director.aws.ec2.EC2Provider;
import com.cloudera.director.spi.v1.database.DatabaseServerProviderMetadata;
import com.cloudera.director.spi.v1.database.util.AbstractDatabaseServerProvider;
import com.cloudera.director.spi.v1.database.util.SimpleDatabaseServerProviderMetadata;
import com.cloudera.director.spi.v1.model.ConfigurationProperty;
import com.cloudera.director.spi.v1.model.ConfigurationValidator;
import com.cloudera.director.spi.v1.model.Configured;
import com.cloudera.director.spi.v1.model.InstanceState;
import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.cloudera.director.spi.v1.model.Resource;
import com.cloudera.director.spi.v1.model.exception.InvalidCredentialsException;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionConditionAccumulator;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionDetails;
import com.cloudera.director.spi.v1.model.exception.TransientProviderException;
import com.cloudera.director.spi.v1.model.exception.UnrecoverableProviderException;
import com.cloudera.director.spi.v1.model.util.CompositeConfigurationValidator;
import com.cloudera.director.spi.v1.model.util.SimpleConfigurationPropertyBuilder;
import com.cloudera.director.spi.v1.util.ConfigurationPropertiesUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database server provider of Amazon RDS instances.
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class RDSProvider extends AbstractDatabaseServerProvider<RDSInstance, RDSInstanceTemplate> {

  private static final Logger LOG = LoggerFactory.getLogger(RDSProvider.class);

  /**
   * The provider configuration properties.
   */
  protected static final List<ConfigurationProperty> CONFIGURATION_PROPERTIES =
      ConfigurationPropertiesUtil.asConfigurationPropertyList(
          RDSProviderConfigurationPropertyToken.values());

  /**
   * The resource provider ID.
   */
  public static final String ID = RDSProvider.class.getCanonicalName();

  /**
   * The resource provider metadata.
   */
  public static final DatabaseServerProviderMetadata METADATA =
      SimpleDatabaseServerProviderMetadata.databaseServerProviderMetadataBuilder()
          .id(ID)
          .name("RDS (Relation Database Service)")
          .description("AWS RDS database server provider")
          .providerClass(RDSProvider.class)
          .providerConfigurationProperties(CONFIGURATION_PROPERTIES)
          .resourceTemplateConfigurationProperties(RDSInstanceTemplate.getConfigurationProperties())
          .resourceDisplayProperties(RDSInstance.getDisplayProperties())
          .supportedDatabaseTypes(getSupportedEngineNamesByDatabaseType().keySet())
          .build();

  /**
   * RDS configuration properties.
   *
   * @see <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/rds/model/CreateDBInstanceRequest.html" />
   */
  // Fully qualifying class name due to compiler bug
  public static enum RDSProviderConfigurationPropertyToken
      implements com.cloudera.director.spi.v1.model.ConfigurationPropertyToken {

    /**
     * Whether to associate a public IP address with instances. Default is <code>false</code>,
     * which differs from the RDS default.
     *
     * @see <a href="http://docs.aws.amazon.com/AmazonVPC/latest/UserGuide/vpc-ip-addressing.html" />
     */
    ASSOCIATE_PUBLIC_IP_ADDRESSES(new SimpleConfigurationPropertyBuilder()
        .configKey("rdsAssociatePublicIpAddresses")
        .name("Associate public IP addresses")
        .widget(ConfigurationProperty.Widget.CHECKBOX)
        .defaultValue("false")
        .defaultDescription("Whether to associate a public IP address with instances or not. " +
            "If this is false, instances are expected to be able to access the internet using a NAT instance. " +
            "Currently the only way to get optimal S3 data transfer performance is to assign " +
            "public IP addresses to instances and not use NAT instances (public subnet setup).")
        .build()),

    /**
     * RDS region. Each region is a separate geographic area. Each region has multiple,
     * isolated locations known as Availability Zones. Default is {@code null}, so we can fall back
     * to the EC2 region.
     *
     * @see <a href="http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-regions-availability-zones.html" />
     */
    REGION(new SimpleConfigurationPropertyBuilder()
        .configKey("rdsRegion")
        .name("RDS region")
        .widget(ConfigurationProperty.Widget.OPENLIST)
        .defaultDescription("The RDS region (defaults to configured EC2 region).")
        .addValidValues(
            "ap-northeast-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "eu-central-1",
            "eu-west-1",
            "sa-east-1",
            "us-east-1",
            "us-west-1",
            "us-west-2")
        .build()),

    /**
     * <p>Custom endpoint identifying a region.</p>
     * <p/>
     * <p>This is critical for Gov. cloud because there is no other way to discover those
     * regions.</p>
     */
    REGION_ENDPOINT(new SimpleConfigurationPropertyBuilder()
        .configKey("rdsRegionEndpoint")
        .name("RDS region endpoint")
        .defaultDescription("<p>RDS region endpoint is an optional URL that Cloudera Director can use to communicate with the AWS Relational Database Service.  AWS provides multiple regional endpoints for RDS as well as separate GovCloud endpoints. </p>For more information see the <a target=\"_blank\" href=\"http://docs.aws.amazon.com/general/latest/gr/rande.html#rds_region\">AWS documentation.</a>")
        .build());

    /**
     * The configuration property.
     */
    private final ConfigurationProperty configurationProperty;

    /**
     * Creates a configuration property token with the specified parameters.
     *
     * @param configurationProperty the configuration property
     */
    private RDSProviderConfigurationPropertyToken(ConfigurationProperty configurationProperty) {
      this.configurationProperty = configurationProperty;
    }

    public ConfigurationProperty unwrap() {
      return configurationProperty;
    }
  }

  /**
   * Configures the specified client.
   *
   * @param configuration               the provider configuration
   * @param accumulator                 the exception accumulator
   * @param client                      the RDS client
   * @param endpoints                   the RDS endpoints
   * @param providerLocalizationContext the resource provider localization context
   * @param verify                      whether to verify the configuration by making an API call
   * @return the configured client
   * @throws InvalidCredentialsException    if the supplied credentials are invalid
   * @throws TransientProviderException     if a transient exception occurs communicating with the
   *                                        provider
   * @throws UnrecoverableProviderException if an unrecoverable exception occurs communicating with
   *                                        the provider
   */
  protected static AmazonRDSClient configureClient(Configured configuration,
      PluginExceptionConditionAccumulator accumulator,
      AmazonRDSClient client,
      RDSEndpoints endpoints,
      LocalizationContext providerLocalizationContext, boolean verify) {
    checkNotNull(client, "client is null");
    try {
      String regionEndpoint =
          configuration.getConfigurationValue(REGION_ENDPOINT, providerLocalizationContext);
      if (regionEndpoint != null) {
        LOG.info("<< Using configured region endpoint: {}", regionEndpoint);
      } else {
        String region = configuration.getConfigurationValue(REGION, providerLocalizationContext);
        if (region == null) {
          region = configuration.getConfigurationValue(
              EC2Provider.EC2ProviderConfigurationPropertyToken.REGION,
              providerLocalizationContext);
        }
        regionEndpoint = getEndpointForRegion(checkNotNull(endpoints, "endpoints is null"),
            region);
      }
      client.setEndpoint(regionEndpoint);

      if (verify) {
        // Attempt to use client, to validate credentials and connectivity
        client.describeDBSecurityGroups();
      }

    } catch (AmazonClientException e) {
      throw AWSExceptions.propagate(e);
    } catch (IllegalArgumentException e) {
      accumulator.addError(REGION.unwrap().getConfigKey(), e.getMessage());
    }
    return client;
  }

  private static String getEndpointForRegion(RDSEndpoints endpoints, String regionName) {
    checkNotNull(regionName, "regionName is null");
    String endpoint = endpoints.apply(regionName);
    if (endpoint == null) {
      throw new IllegalArgumentException(String.format(
          "Endpoint unknown for region %s. Please configure it as a custom " +
              "RDS endpoint.", regionName));
    }
    return endpoint;
  }

  private final AmazonRDSClient client;

  @SuppressWarnings("PMD.UnusedPrivateField")
  private final AmazonIdentityManagementClient identityManagementClient;

  private final boolean associatePublicIpAddresses;

  private final ConfigurationValidator resourceTemplateConfigurationValidator;

  /**
   * Construct a new provider instance and validate all configurations.
   *
   * @param configuration            the configuration
   * @param endpoints                the RDS endpoints
   * @param client                   the EC2 client
   * @param identityManagementClient the AIM client
   * @param cloudLocalizationContext the parent cloud localization context
   */
  public RDSProvider(Configured configuration,
      RDSEndpoints endpoints,
      AmazonRDSClient client,
      AmazonIdentityManagementClient identityManagementClient,
      LocalizationContext cloudLocalizationContext) {
    super(configuration, METADATA, cloudLocalizationContext);
    LocalizationContext localizationContext = getLocalizationContext();
    this.identityManagementClient = checkNotNull(identityManagementClient,
        "identityManagementClient is null");

    PluginExceptionConditionAccumulator accumulator = new PluginExceptionConditionAccumulator();
    this.client =
        configureClient(configuration, accumulator, client, endpoints, localizationContext, false);
    if (accumulator.hasError()) {
      PluginExceptionDetails pluginExceptionDetails =
          new PluginExceptionDetails(accumulator.getConditionsByKey());
      throw new UnrecoverableProviderException("Provider initialization failed",
          pluginExceptionDetails);
    }

    this.associatePublicIpAddresses = Boolean.parseBoolean(
        getConfigurationValue(RDSProviderConfigurationPropertyToken.ASSOCIATE_PUBLIC_IP_ADDRESSES,
            localizationContext));

    this.resourceTemplateConfigurationValidator =
        new CompositeConfigurationValidator(METADATA.getResourceTemplateConfigurationValidator(),
            new RDSInstanceTemplateConfigurationValidator(this));
  }

  /**
   * Returns the RDS client.
   *
   * @return the RDS client
   */
  public AmazonRDSClient getClient() {
    return client;
  }

  @Override
  public ConfigurationValidator getResourceTemplateConfigurationValidator() {
    return resourceTemplateConfigurationValidator;
  }

  @Override
  public Resource.Type getResourceType() {
    return RDSInstance.TYPE;
  }

  @Override
  public RDSInstanceTemplate createResourceTemplate(
      String name, Configured configuration, Map<String, String> tags) {
    return new RDSInstanceTemplate(name, configuration, tags, getLocalizationContext());
  }

  @Override
  public void allocate(RDSInstanceTemplate template, Collection<String> virtualInstanceIds,
      int minCount) throws InterruptedException {
    int instanceCount = virtualInstanceIds.size();

    LOG.info(">> Requesting {} instances for {}", instanceCount, template);
    for (String virtualInstanceId : virtualInstanceIds) {
      CreateDBInstanceRequest request = buildCreateRequest(template, virtualInstanceId);
      client.createDBInstance(request);
    }
  }

  @Override
  public Collection<RDSInstance> find(final RDSInstanceTemplate template,
      Collection<String> virtualInstanceIds) throws InterruptedException {

    final Collection<RDSInstance> rdsInstances =
        Lists.newArrayListWithExpectedSize(virtualInstanceIds.size());

    forEachInstance(virtualInstanceIds, new InstanceHandler() {
      @Override
      public void handle(DBInstance dbInstance) {
        String virtualInstanceId = checkInstanceIsManagedByDirector(dbInstance, template);
        rdsInstances.add(new RDSInstance(template, virtualInstanceId, dbInstance));
      }
    });

    return rdsInstances;
  }

  @Override
  public void delete(RDSInstanceTemplate template, Collection<String> virtualInstanceIds)
      throws InterruptedException {

    if (virtualInstanceIds.isEmpty()) {
      return;
    }

    for (String virtualInstanceId : virtualInstanceIds) {
      LOG.info(">> Terminating {}", virtualInstanceId);

      DeleteDBInstanceRequest request = new DeleteDBInstanceRequest()
          .withDBInstanceIdentifier(virtualInstanceId);
      if (template.isSkipFinalSnapshot().or(false)) {
        request.setSkipFinalSnapshot(true);
      } else {
        String snapshotIdentifier = String.format("%s-director-final-snapshot-%d",
            virtualInstanceId, System.currentTimeMillis());
        request.setFinalDBSnapshotIdentifier(snapshotIdentifier);
      }

      try {
        DBInstance deletedDbInstance = client.deleteDBInstance(request);
        LOG.info("<< Result {}", deletedDbInstance);
      } catch (DBInstanceNotFoundException e) {
        LOG.warn("<< Instance {} was not found, assuming already deleted", virtualInstanceId);
      }
    }
  }

  @Override
  public Map<String, InstanceState> getInstanceState(
      RDSInstanceTemplate template, Collection<String> virtualInstanceIds) {
    Map<String, InstanceState> instanceStateByVirtualInstanceId =
        Maps.newHashMapWithExpectedSize(virtualInstanceIds.size());

    // RDS does not allow batching of DB instance status requests.
    for (String virtualInstanceId : virtualInstanceIds) {
      InstanceState instanceState;
      try {
        DescribeDBInstancesResult result = client.describeDBInstances(
            new DescribeDBInstancesRequest()
                .withDBInstanceIdentifier(virtualInstanceId));
        LOG.info("<< Result: {}", result);

        // Paging not required, should only ever be one instance returned
        if (result.getDBInstances().size() > 0) {
          DBInstance dbInstance = result.getDBInstances().get(0);
          RDSStatus status = RDSStatus.valueOfRDSString(dbInstance.getDBInstanceStatus());
          instanceState = RDSInstanceState.fromRdsStatus(status);
        } else {
          instanceState = RDSInstanceState.fromRdsStatus(null);
        }
      } catch (DBInstanceNotFoundException e) {
        instanceState = RDSInstanceState.fromRdsStatus(null);
      }
      instanceStateByVirtualInstanceId.put(virtualInstanceId, instanceState);
    }

    return instanceStateByVirtualInstanceId;
  }

  /**
   * Returns a DB instance request based on the specified template.
   *
   * @param template          the template
   * @param virtualInstanceId the virtual instance ID
   * @return a DB instance request based on the specified template
   */
  private CreateDBInstanceRequest buildCreateRequest(RDSInstanceTemplate template,
      String virtualInstanceId) {
    CreateDBInstanceRequest request = new CreateDBInstanceRequest()
        .withDBInstanceIdentifier(virtualInstanceId)
        .withDBInstanceClass(template.getInstanceClass())
        .withDBSubnetGroupName(template.getDbSubnetGroupName())
        .withVpcSecurityGroupIds(template.getVpcSecurityGroupIds())
        .withPubliclyAccessible(associatePublicIpAddresses)
        .withAllocatedStorage(template.getAllocatedStorage())
        .withEngine(template.getEngine())
        .withMasterUsername(template.getAdminUser())
        .withMasterUserPassword(template.getAdminPassword())  // masterPassword in AWS SDK 1.9+
        .withTags(convertToTags(template.getTags(), template, virtualInstanceId));

    if (template.getEngineVersion().isPresent()) {
      request = request.withEngineVersion(template.getEngineVersion().get());
    }
    if (template.getAvailabilityZone().isPresent()) {
      request = request.withAvailabilityZone(template.getAvailabilityZone().get());
    }
    if (template.getAutoMinorVersionUpgrade().isPresent()) {
      request = request.withAutoMinorVersionUpgrade(template.getAutoMinorVersionUpgrade().get());
    }
    if (template.getBackupRetentionPeriod().isPresent()) {
      request = request.withBackupRetentionPeriod(template.getBackupRetentionPeriod().get());
    }
    if (template.getDbName().isPresent()) {
      request = request.withDBName(template.getDbName().get());
    }
    if (template.getDbParameterGroupName().isPresent()) {
      request = request.withDBParameterGroupName(template.getDbParameterGroupName().get());
    }
    if (template.getEngineVersion().isPresent()) {
      request = request.withEngineVersion(template.getEngineVersion().get());
    }
    if (template.getLicenseModel().isPresent()) {
      request = request.withLicenseModel(template.getLicenseModel().get());
    }
    if (template.isMultiAZ().isPresent()) {
      request = request.withMultiAZ(template.isMultiAZ().get());
    }
    if (template.getOptionGroupName().isPresent()) {
      request = request.withOptionGroupName(template.getOptionGroupName().get());
    }
    if (template.getPort().isPresent()) {
      request = request.withPort(template.getPort().get());
    }
    if (template.getPreferredBackupWindow().isPresent()) {
      request = request.withPreferredBackupWindow(template.getPreferredBackupWindow().get());
    }
    if (template.getPreferredMaintenanceWindow().isPresent()) {
      request =
          request.withPreferredMaintenanceWindow(template.getPreferredMaintenanceWindow().get());
    }
    if (template.isPubliclyAccessible().isPresent()) {
      request = request.withPubliclyAccessible(template.isPubliclyAccessible().get());
    }

    return request;
  }

  private Collection<Tag> convertToTags(Map<String, String> templateTags,
      RDSInstanceTemplate template, String instanceId) {
    Collection<Tag> tags = Lists.newArrayList();
    tags.add(new Tag().withKey(InstanceTags.INSTANCE_NAME).withValue(String.format("%s-%s",
        template.getInstanceNamePrefix(), instanceId)));
    tags.add(new Tag().withKey(Tags.CLOUDERA_DIRECTOR_ID).withValue(instanceId));
    tags.add(new Tag().withKey(Tags.CLOUDERA_DIRECTOR_TEMPLATE_NAME).withValue(template.getName()));
    for (Map.Entry<String, String> e : templateTags.entrySet()) {
      tags.add(new Tag().withKey(e.getKey()).withValue(e.getValue()));
    }
    return tags;
  }

  /**
   * Performs a sequence of strict instance ownership checks to avoid any potential harmful
   * accidents.
   *
   * @param instance the instance
   * @param template the template from which the instance was created, or <code>null</code>
   *                 if it is unknown (such as during a delete call)
   * @return the virtual instance ID
   */
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private String checkInstanceIsManagedByDirector(DBInstance instance,
      RDSInstanceTemplate template) {
    // TODO perform any desired tag validation
    return instance.getDBInstanceIdentifier();
  }

  /**
   * Represents a callback that can be applied to each instance of
   * a {@code DescribeDBInstancesResult}.
   */
  private interface InstanceHandler {

    /**
     * Handles the specified instance.
     *
     * @param dbInstance the instance
     */
    void handle(DBInstance dbInstance);
  }

  /**
   * Iterates through the instances identified by the specified virtual instance IDs
   * and calls the specified handler on each instance.
   *
   * @param virtualInstanceIds the virtual instance IDs
   * @param instanceHandler    the instance handler
   */
  private void forEachInstance(Collection<String> virtualInstanceIds,
      RDSProvider.InstanceHandler instanceHandler) {
    // TODO RDS does not currently support the withFilters parameter, so we have to load one at a
    // time by id
    //DescribeDBInstancesResult result = client.describeDBInstances(new DescribeDBInstancesRequest()
    //    .withFilters(new Filter().withFilterName("tag:" + Tags.CLOUDERA_DIRECTOR_ID)
    //        .withFilterValue(virtualInstanceIds)));
    for (String virtualInstanceId : virtualInstanceIds) {
      DescribeDBInstancesResult result = client.describeDBInstances(new DescribeDBInstancesRequest()
          .withDBInstanceIdentifier(virtualInstanceId));
      forEachInstance(result, instanceHandler);
    }
  }

  /**
   * Iterates through the instances in the specified {@code DescribeInstancesResult}
   * and calls the specified handler on each instance.
   *
   * @param result          the {@code DescribeInstancesResult}
   * @param instanceHandler the instance handler
   */
  private void forEachInstance(DescribeDBInstancesResult result, InstanceHandler instanceHandler) {
    for (DBInstance dbInstance : result.getDBInstances()) {
      instanceHandler.handle(dbInstance);
    }
  }
}
