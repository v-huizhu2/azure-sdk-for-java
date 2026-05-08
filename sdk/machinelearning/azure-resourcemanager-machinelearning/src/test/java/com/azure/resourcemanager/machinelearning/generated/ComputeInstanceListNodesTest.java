// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.resourcemanager.machinelearning.generated;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.test.http.MockHttpResponse;
import com.azure.core.util.Context;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.applicationinsights.ApplicationInsightsManager;
import com.azure.resourcemanager.applicationinsights.models.ApplicationInsightsComponent;
import com.azure.resourcemanager.applicationinsights.models.ApplicationType;
import com.azure.resourcemanager.keyvault.KeyVaultManager;
import com.azure.resourcemanager.keyvault.models.Vault;
import com.azure.resourcemanager.machinelearning.MachineLearningManager;
import com.azure.resourcemanager.machinelearning.models.ComputeInstance;
import com.azure.resourcemanager.machinelearning.models.ComputeInstanceProperties;
import com.azure.resourcemanager.machinelearning.models.ComputeResource;
import com.azure.resourcemanager.machinelearning.models.ManagedServiceIdentity;
import com.azure.resourcemanager.machinelearning.models.ManagedServiceIdentityType;
import com.azure.resourcemanager.machinelearning.models.Workspace;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.storage.StorageManager;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.azure.resourcemanager.storage.models.StorageAccountSkuType;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/**
 * Reproduces https://github.com/Azure/azure-rest-api-specs-examples/issues/5127
 *
 * <p>Calling {@code computes().listNodes(...)} against a compute whose {@code computeType} is
 * {@code ComputeInstance} returns HTTP 400 with the message
 * "ComputeListNodes with computeType ComputeInstance is not supported." The SDK surfaces this
 * service-side failure as a {@link ManagementException}.
 *
 * <p>This class contains:
 * <ul>
 *   <li>{@link #testListNodesOnComputeInstanceThrows400()} – an offline mock-based test that
 *       always runs.</li>
 *   <li>A live counterpart, {@link #testListNodesOnLiveComputeInstanceThrows400()}, gated by
 *       env var {@code AZURE_LIVE_REPRO_5127=true}. {@link #setUp()} provisions resources via
 *       the Java SDK and {@link #tearDown()} deletes the resource group.</li>
 * </ul>
 */
public final class ComputeInstanceListNodesTest {

    // -------- Live-mode configuration --------
    private static final String LIVE_FLAG = "AZURE_LIVE_REPRO_5127";
    private static final String SUBSCRIPTION_ID_ENV = "AZURE_SUBSCRIPTION_ID";
    private static final String TENANT_ID_ENV = "AZURE_TENANT_ID";
    // Resource group and workspace names are randomized at runtime in setUp() to avoid
    // collisions with previous in-flight deletions / soft-deleted records.
    private static final int SUFFIX = ThreadLocalRandom.current().nextInt(10_000, 99_999);
    private static String RG = "testrg" + SUFFIX;
    private static final String LOCATION = "eastus";
    private static final String CI_NAME = "compute" + SUFFIX;
    private static final String[] VM_SIZES
        = { "Standard_DS3_v2", "Standard_DS2_v2", "Standard_DS1_v2", "Standard_E2s_v3", "Standard_F2s_v2" };
    private static String WS = "workspaces" + SUFFIX;

    private static MachineLearningManager liveManager;

    // ===================================================================
    //                           OFFLINE TEST
    // ===================================================================
    @Test
    public void testListNodesOnComputeInstanceThrows400() {
        final String errorBody = "{\"error\":{\"code\":\"BadRequest\","
            + "\"message\":\"ComputeListNodes with computeType ComputeInstance is not supported.\"}}";

        HttpClient httpClient
            = request -> Mono.just(new MockHttpResponse(request, 400, errorBody.getBytes(StandardCharsets.UTF_8)));

        MachineLearningManager manager = MachineLearningManager.configure()
            .withHttpClient(httpClient)
            .authenticate(tokenRequestContext -> Mono.just(new AccessToken("this_is_a_token", OffsetDateTime.MAX)),
                new AzureProfile("", "", AzureEnvironment.AZURE));

        ManagementException exception = Assertions.assertThrows(ManagementException.class,
            () -> manager.computes().listNodes(RG, WS, CI_NAME, Context.NONE).stream().findFirst());

        Assertions.assertEquals(400, exception.getResponse().getStatusCode());
        Assertions.assertNotNull(exception.getValue());
        Assertions.assertEquals("ComputeListNodes with computeType ComputeInstance is not supported.",
            exception.getValue().getMessage());
    }

    // ===================================================================
    //                LIVE SETUP / VERIFY / TEST / CLEANUP
    // ===================================================================
    @BeforeAll
    static void setUp() {
        if (!isLiveMode()) {
            return;
        }
        String subscriptionId = System.getenv(SUBSCRIPTION_ID_ENV);
        Assertions.assertNotNull(subscriptionId, "AZURE_SUBSCRIPTION_ID must be set when " + LIVE_FLAG + "=true");
        String tenantId = System.getenv(TENANT_ID_ENV);
        Assertions.assertNotNull(tenantId, "AZURE_TENANT_ID must be set when " + LIVE_FLAG + "=true");

        TokenCredential credential = new DefaultAzureCredentialBuilder().tenantId(tenantId).build();
        AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);

        ResourceManager resourceManager
            = ResourceManager.authenticate(credential, profile).withSubscription(subscriptionId);
        StorageManager storageManager = StorageManager.authenticate(credential, profile);
        KeyVaultManager keyVaultManager = KeyVaultManager.authenticate(credential, profile);
        ApplicationInsightsManager aiManager = ApplicationInsightsManager.authenticate(credential, profile);
        liveManager = MachineLearningManager.authenticate(credential, profile);

        int suffix = ThreadLocalRandom.current().nextInt(10_000, 99_999);
        String storageName = "repro5127st" + suffix;
        String kvName = "repro5127kv" + suffix;
        String aiName = "repro5127ai" + suffix;
        // Randomize RG and workspace name to avoid collisions across runs (in-flight RG deletes,
        // and soft-deleted workspace records that block name reuse for 14 days).
        RG = "testrg5127" + suffix;
        WS = "repro5127ws" + suffix;

        System.out.println("==> Creating resource group: " + RG);
        ResourceGroup rg = resourceManager.resourceGroups().define(RG).withRegion(LOCATION).create();

        System.out.println("==> Creating Storage Account: " + storageName + " (shared key disabled)");
        StorageAccount storage = storageManager.storageAccounts()
            .define(storageName)
            .withRegion(LOCATION)
            .withExistingResourceGroup(rg)
            .withSku(StorageAccountSkuType.STANDARD_LRS)
            .disableSharedKeyAccess()
            .create();

        System.out.println("==> Creating Key Vault: " + kvName);
        Vault keyVault = keyVaultManager.vaults()
            .define(kvName)
            .withRegion(LOCATION)
            .withExistingResourceGroup(RG)
            .withRoleBasedAccessControl()
            .withEmptyAccessPolicy()
            .create();

        System.out.println("==> Creating Application Insights: " + aiName);
        ApplicationInsightsComponent ai = aiManager.components()
            .define(aiName)
            .withRegion(LOCATION)
            .withExistingResourceGroup(RG)
            .withKind("web")
            .withApplicationType(ApplicationType.WEB)
            .create();

        System.out.println("==> Creating AML workspace: " + WS + " (~2 min)");
        liveManager.workspaces()
            .define(WS)
            .withExistingResourceGroup(RG)
            .withRegion(LOCATION)
            .withIdentity(new ManagedServiceIdentity().withType(ManagedServiceIdentityType.SYSTEM_ASSIGNED))
            .withStorageAccount(storage.id())
            .withKeyVault(keyVault.id())
            .withApplicationInsights(ai.id())
            .create();

        ManagementException lastError = null;
        for (String vmSize : VM_SIZES) {
            try {
                System.out.println("==> Creating ComputeInstance: " + CI_NAME + " (vmSize=" + vmSize + ", ~3-5 min)");
                liveManager.computes()
                    .define(CI_NAME)
                    .withExistingWorkspace(RG, WS)
                    .withRegion(LOCATION)
                    .withProperties(
                        new ComputeInstance().withProperties(new ComputeInstanceProperties().withVmSize(vmSize)))
                    .create();
                lastError = null;
                break;
            } catch (ManagementException ex) {
                lastError = ex;
                if (ex.getMessage() != null && ex.getMessage().contains("OutOfCapacity")) {
                    System.out.println("    OutOfCapacity for " + vmSize + ", trying next size...");
                } else {
                    throw ex;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }

        verifyResources();
    }

    private static void verifyResources() {
        System.out.println("==> Verifying resources");
        Workspace ws = liveManager.workspaces().getByResourceGroup(RG, WS);
        Assertions.assertNotNull(ws, "workspace " + WS + " not found");
        Assertions.assertEquals(WS, ws.name());

        ComputeResource compute = liveManager.computes().get(RG, WS, CI_NAME);
        Assertions.assertNotNull(compute, "compute " + CI_NAME + " not found");
        Assertions.assertTrue(compute.properties() instanceof ComputeInstance,
            "expected ComputeInstance but got " + compute.properties().getClass().getSimpleName());
        System.out.println("   workspace + ComputeInstance OK");
    }

    /** Live counterpart of the offline test: hits the real ARM endpoint. */
    @Test
    @EnabledIfEnvironmentVariable(named = LIVE_FLAG, matches = "(?i)true")
    public void testListNodesOnLiveComputeInstanceThrows400() {
        Assertions.assertNotNull(liveManager, "liveManager not initialized; setUp must have run");

        System.out.println("==> Calling listNodes on ComputeInstance: RG=" + RG + ", WS=" + WS + ", CI=" + CI_NAME);
        ManagementException exception = Assertions.assertThrows(ManagementException.class,
            () -> liveManager.computes().listNodes(RG, WS, CI_NAME, Context.NONE).stream().findFirst());

        System.out.println("==> Got expected 400: " + exception.getValue().getMessage());
        Assertions.assertEquals(400, exception.getResponse().getStatusCode());
        Assertions.assertNotNull(exception.getValue());
        Assertions.assertEquals("ComputeListNodes with computeType ComputeInstance is not supported.",
            exception.getValue().getMessage());
        System.out.println("==> Test PASSED: listNodes correctly returns 400 for ComputeInstance");
    }

    @AfterAll
    static void tearDown() {
        if (!isLiveMode()) {
            return;
        }
        String subscriptionId = System.getenv(SUBSCRIPTION_ID_ENV);
        if (subscriptionId == null) {
            return;
        }
        String tenantId = System.getenv(TENANT_ID_ENV);
        try {
            TokenCredential credential = new DefaultAzureCredentialBuilder().tenantId(tenantId).build();
            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            ResourceManager resourceManager
                = ResourceManager.authenticate(credential, profile).withSubscription(subscriptionId);

            System.out.println("==> Deleting resource group: " + RG + " (async, fire-and-forget)");
            resourceManager.resourceGroups().beginDeleteByName(RG);
        } catch (RuntimeException ex) {
            System.err.println("Cleanup failed (manual cleanup may be required): " + ex.getMessage());
        }
    }

    private static boolean isLiveMode() {
        return "true".equalsIgnoreCase(System.getenv(LIVE_FLAG));
    }
}
