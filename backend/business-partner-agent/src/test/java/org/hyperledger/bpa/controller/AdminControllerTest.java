/*
 * Copyright (c) 2020-2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository at
 * https://github.com/hyperledger-labs/business-partner-agent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.bpa.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.ledger.DidVerkeyResponse;
import org.hyperledger.aries.api.schema.SchemaSendResponse;
import org.hyperledger.bpa.api.aries.SchemaAPI;
import org.hyperledger.bpa.controller.api.admin.AddSchemaRequest;
import org.hyperledger.bpa.controller.api.admin.AddTrustedIssuerRequest;
import org.hyperledger.bpa.controller.api.admin.TrustedIssuer;
import org.hyperledger.bpa.controller.api.admin.UpdateTrustedIssuerRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@MicronautTest
public class AdminControllerTest {

    private final String schemaId1 = "NZhb9EqpN9a6gkHge9fTmv:1:first:0.1";
    private final String schemaId2 = "NZhb9EqpN9a6gkHge9fTmv:2:second:0.1";
    private final String schemaId3 = "NZhb9EqpN9a6gkHge9fTmv:3:third:0.1";

    @Value("${bpa.did.prefix}")
    String didPrefix;

    @Inject
    @Client("/api/admin/schema")
    HttpClient client;

    @Inject
    AriesClient ac; // already a mock

    @Test
    void testAddSchemaWithRestriction() throws Exception {
        mockGetSchemaAndVerkey();

        // add schema
        HttpResponse<SchemaAPI> addedSchema = addSchemaWithRestriction(schemaId1);
        Assertions.assertEquals(HttpStatus.OK, addedSchema.getStatus());
        Assertions.assertTrue(addedSchema.getBody().isPresent());

        // check added schema
        SchemaAPI schema = getSchema(addedSchema.getBody().get().getId());
        Assertions.assertEquals(schemaId1, schema.getSchemaId());
        Assertions.assertNotNull(schema.getTrustedIssuer());
        Assertions.assertEquals(1, schema.getTrustedIssuer().size());
        Assertions.assertEquals(didPrefix + "issuer1", schema.getTrustedIssuer().get(0).getIssuerDid());

        // add a restriction to the schema
        URI uri = UriBuilder.of("/{id}/trustedIssuer")
                .expand(Map.of("id", schema.getId().toString()));
        addRestriction(uri, "issuer2", "Demo Bank");

        // check if the restriction was added
        schema = getSchema(addedSchema.getBody().get().getId());
        Assertions.assertNotNull(schema.getTrustedIssuer());
        Assertions.assertEquals(2, schema.getTrustedIssuer().size());
        Assertions.assertEquals(didPrefix + "issuer2", schema.getTrustedIssuer().get(1).getIssuerDid());

        // try adding the same restriction twice
        Assertions.assertThrows(HttpClientResponseException.class,
                () -> addRestriction(uri, "issuer2", null));

        // delete the first restriction
        URI delete = UriBuilder.of("/{id}/trustedIssuer/{trustedIssuerId}")
                .expand(Map.of(
                        "id", schema.getId().toString(),
                        "trustedIssuerId", schema.getTrustedIssuer().get(0).getId().toString()));
        client.toBlocking().exchange(HttpRequest.DELETE(delete.toString()));

        // check if the first restriction was deleted
        schema = getSchema(addedSchema.getBody().get().getId());
        Assertions.assertNotNull(schema.getTrustedIssuer());
        Assertions.assertEquals(1, schema.getTrustedIssuer().size());
        Assertions.assertEquals(didPrefix + "issuer2", schema.getTrustedIssuer().get(0).getIssuerDid());

        // update the remaining restriction
        URI put = UriBuilder.of("/{id}/trustedIssuer/{trustedIssuerId}")
                .expand(Map.of(
                        "id", schema.getId().toString(),
                        "trustedIssuerId", schema.getTrustedIssuer().get(0).getId().toString()));
        client.toBlocking().exchange(HttpRequest.PUT(put, new UpdateTrustedIssuerRequest("Dummy Bank")));

        // check if the label was updated
        schema = getSchema(addedSchema.getBody().get().getId());
        Assertions.assertEquals("Dummy Bank", schema.getTrustedIssuer().get(0).getLabel());

        // delete schema should fail because it still has a restriction
        UUID deleteId = schema.getId();
        Assertions.assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.DELETE("/" + deleteId)));

        // delete the second restriction
        delete = UriBuilder.of("/{id}/trustedIssuer/{trustedIssuerId}")
                .expand(Map.of(
                        "id", schema.getId().toString(),
                        "trustedIssuerId", schema.getTrustedIssuer().get(0).getId().toString()));
        client.toBlocking().exchange(HttpRequest.DELETE(delete.toString()));

        // delete the schema
        client.toBlocking().exchange(HttpRequest.DELETE("/" + deleteId));
        // check if the schema was deleted
        Assertions.assertThrows(HttpClientResponseException.class, () -> getSchema(deleteId));
    }

    @Test
    void testAddRestrictionTwice() throws Exception {
        mockGetSchemaAndVerkey();

        SchemaAPI schema1 = addSchemaWithRestriction(schemaId2).getBody().orElseThrow();
        SchemaAPI schema2 = addSchemaNoRestriction().getBody().orElseThrow();

        URI uri1 = UriBuilder.of("/{id}/trustedIssuer").expand(Map.of("id", schema1.getId().toString()));
        URI uri2 = UriBuilder.of("/{id}/trustedIssuer").expand(Map.of("id", schema2.getId().toString()));

        // try adding the same restriction to schema1 twice
        Assertions.assertThrows(HttpClientResponseException.class,
                () -> addRestriction(uri1, "issuer1", null));

        addRestriction(uri2, "issuer1", null);

        // check if the restriction was added
        schema2 = getSchema(schema2.getId());
        Assertions.assertNotNull(schema2.getTrustedIssuer());
        Assertions.assertEquals(1, schema2.getTrustedIssuer().size());
        Assertions.assertEquals(didPrefix + "issuer1", schema2.getTrustedIssuer().get(0).getIssuerDid());
    }

    @Test
    void testAddRestrictionToNonExistingSchema() {
        URI uri = UriBuilder.of("/{id}/trustedIssuer").expand(Map.of("id", UUID.randomUUID().toString()));
        Assertions.assertThrows(HttpClientResponseException.class, () -> addRestriction(uri, "something", null));
    }

    private SchemaAPI getSchema(@NonNull UUID id) {
        return client.toBlocking()
                .retrieve(HttpRequest.GET("/" + id), SchemaAPI.class);
    }

    private HttpResponse<SchemaAPI> addSchemaWithRestriction(String schemaId) {
        return client.toBlocking()
                .exchange(HttpRequest.POST("",
                        AddSchemaRequest.builder()
                                .schemaId(schemaId)
                                .defaultAttributeName("name")
                                .label("Demo Bank")
                                .trustedIssuer(List.of(AddTrustedIssuerRequest
                                        .builder()
                                        .issuerDid("issuer1")
                                        .label("Demo Issuer")
                                        .build()))
                                .build()),
                        SchemaAPI.class);
    }

    private HttpResponse<SchemaAPI> addSchemaNoRestriction() {
        return client.toBlocking()
                .exchange(HttpRequest.POST("",
                        AddSchemaRequest.builder()
                                .schemaId(schemaId3)
                                .defaultAttributeName("other")
                                .label("Demo Corp")
                                .build()),
                        SchemaAPI.class);
    }

    private void addRestriction(URI uri, String issuerDid, String label) {
        client.toBlocking()
                .exchange(HttpRequest.POST(uri,
                        AddTrustedIssuerRequest.builder()
                                .issuerDid(issuerDid)
                                .label(label)
                                .build()),
                        TrustedIssuer.class);
    }

    private void mockGetSchemaAndVerkey() throws IOException {
        Mockito.when(ac.schemasGetById(schemaId1)).thenReturn(Optional.of(SchemaSendResponse.Schema
                .builder()
                .id(schemaId1)
                .seqNo(1)
                .attrNames(List.of("name"))
                .name("dummy")
                .build()));

        Mockito.when(ac.schemasGetById(schemaId2)).thenReturn(Optional.of(SchemaSendResponse.Schema
                .builder()
                .id(schemaId2)
                .seqNo(1)
                .attrNames(List.of("other1"))
                .name("dummy1")
                .build()));

        Mockito.when(ac.schemasGetById(schemaId3)).thenReturn(Optional.of(SchemaSendResponse.Schema
                .builder()
                .id(schemaId3)
                .seqNo(1)
                .attrNames(List.of("other2"))
                .name("dummy2")
                .build()));

        Mockito.when(ac.ledgerDidVerkey(Mockito.anyString()))
                .thenReturn(Optional.of(new DidVerkeyResponse("verkey")));
    }
}
