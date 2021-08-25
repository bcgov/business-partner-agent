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

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.bpa.api.PartnerAPI;
import org.hyperledger.bpa.api.aries.AriesProofExchange;
import org.hyperledger.bpa.controller.api.partner.*;
import org.hyperledger.bpa.impl.ChatMessageManager;
import org.hyperledger.bpa.impl.ChatMessageService;
import org.hyperledger.bpa.impl.PartnerManager;
import org.hyperledger.bpa.impl.activity.PartnerLookup;
import org.hyperledger.bpa.impl.aries.ConnectionManager;
import org.hyperledger.bpa.impl.aries.HolderCredentialManager;
import org.hyperledger.bpa.impl.aries.PartnerCredDefLookup;
import org.hyperledger.bpa.impl.aries.ProofManager;
import org.hyperledger.bpa.model.ChatMessage;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller("/api/partners")
@Tag(name = "Partner (Connection) Management")
@Validated
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.IO)
public class PartnerController {

    @Inject
    PartnerManager pm;

    @Inject
    PartnerLookup partnerLookup;

    @Inject
    HolderCredentialManager credM;

    @Inject
    ProofManager proofM;

    @Inject
    ConnectionManager cm;

    @Inject
    PartnerCredDefLookup credLookup;

    @Inject
    ChatMessageManager chatMessageManager;

    @Inject
    ChatMessageService chatMessageService;

    /**
     * Get known partners
     *
     * @param schemaId Filter Partners by schema id
     * @return list of partners
     */
    @Get
    public HttpResponse<List<PartnerAPI>> getPartners(
            @Parameter(description = "schema id") @Nullable @QueryValue String schemaId) {
        if (StringUtils.isNotBlank(schemaId)) {
            return HttpResponse.ok(credLookup.getIssuersFor(schemaId));
        }
        return HttpResponse.ok(pm.getPartners());
    }

    /**
     * Get partner by id
     *
     * @param id {@link UUID} the partner id
     * @return partner
     */
    @Get("/{id}")
    public HttpResponse<PartnerAPI> getPartnerById(@PathVariable String id) {
        Optional<PartnerAPI> partner = pm.getPartnerById(UUID.fromString(id));
        if (partner.isPresent()) {
            return HttpResponse.ok(partner.get());
        }
        return HttpResponse.notFound();
    }

    /**
     * Update partner
     *
     * @param id     {@link UUID} the partner id
     * @param update {@link UpdatePartnerRequest}
     * @return {@link PartnerAPI}
     */
    @Put("/{id}")
    public HttpResponse<PartnerAPI> updatePartner(
            @PathVariable String id,
            @Body UpdatePartnerRequest update) {
        Optional<PartnerAPI> partner = pm.updatePartner(UUID.fromString(id), update);
        if (partner.isPresent()) {
            return HttpResponse.ok(partner.get());
        }
        return HttpResponse.notFound();
    }

    /**
     * Update partner's did
     *
     * @param id     {@link UUID} the partner id
     * @param update {@link UpdatePartnerRequest}
     * @return {@link PartnerAPI}
     */
    @Put("/{id}/did")
    public HttpResponse<PartnerAPI> updatePartnerDid(
            @PathVariable String id,
            @Body UpdatePartnerDidRequest update) {
        Optional<PartnerAPI> partner = pm.updatePartnerDid(UUID.fromString(id), update.getDid());
        if (partner.isPresent()) {
            return HttpResponse.ok(partner.get());
        }
        return HttpResponse.notFound();
    }

    /**
     * Remove partner
     *
     * @param id {@link UUID} the partner id
     * @return HTTP status, no Body
     */
    @Delete("/{id}")
    public HttpResponse<Void> removePartner(@PathVariable String id) {
        pm.removePartnerById(UUID.fromString(id));
        return HttpResponse.ok();
    }

    /**
     * Add a new partner
     *
     * @param request {@link AddPartnerRequest}
     * @return {@link PartnerAPI}
     */
    @Post
    public HttpResponse<PartnerAPI> addPartner(@Body AddPartnerRequest request) {
        return HttpResponse.created(pm.addPartnerFlow(request));
    }

    /**
     * Lookup/Preview a partners public profile before adding
     *
     * @param did the partners did
     * @return {@link PartnerAPI}
     */
    @Get("/lookup/{did}")
    public HttpResponse<PartnerAPI> lookupPartner(@PathVariable String did) {
        return HttpResponse.ok(partnerLookup.lookupPartner(did));
    }

    /**
     * Reload/Re- lookup a partners public profile
     *
     * @param id {@link UUID} the partner id
     * @return {@link PartnerAPI}
     */
    @Get("/{id}/refresh")
    public HttpResponse<PartnerAPI> refreshPartner(@PathVariable String id) {
        final Optional<PartnerAPI> partner = pm.refreshPartner(UUID.fromString(id));
        if (partner.isPresent()) {
            return HttpResponse.ok(partner.get());
        }
        return HttpResponse.notFound();
    }

    /**
     * Aries: Request credential from partner
     *
     * @param id      {@link UUID} the partner id
     * @param credReq {@link RequestCredentialRequest}
     * @return HTTP status
     */
    @Post("/{id}/credential-request")
    public HttpResponse<Void> requestCredential(
            @PathVariable String id,
            @Body RequestCredentialRequest credReq) {
        credM.sendCredentialRequest(
                UUID.fromString(id),
                UUID.fromString(credReq.getDocumentId()));
        return HttpResponse.ok();
    }

    /**
     * Aries: List proof exchange records
     *
     * @param id {@link UUID} the partner id
     * @return HTTP status
     */
    @Get("/{id}/proof-exchanges")
    public HttpResponse<List<AriesProofExchange>> getPartnerProofs(@PathVariable String id) {
        return HttpResponse.ok(proofM.listPartnerProofs(UUID.fromString(id)));
    }

    /**
     * Manual connection flow. Accept partner connection request
     *
     * @param id {@link UUID} the partner id
     * @return HTTP status, no Body
     */
    @Put("/{id}/accept")
    public HttpResponse<Void> acceptPartnerRequest(@PathVariable String id) {
        pm.acceptPartner(UUID.fromString(id));
        return HttpResponse.ok();
    }

    /**
     * Aries: Create a connection-invitation
     *
     * @param req {@link CreatePartnerInvitationRequest}
     * @return {@link PartnerAPI}
     */
    @Post("/invitation")
    public HttpResponse<?> requestConnectionInvitation(
            @Body CreatePartnerInvitationRequest req) {
        return HttpResponse.ok(cm.createConnectionInvitation(req));
    }

    /**
     * Send chat message to partner
     *
     * @param id  {@link UUID} the partner id
     * @param msg {@link SendMessageRequest}
     * @return HTTP status
     */
    @Post("/{id}/messages")
    public HttpResponse<Void> sendMessage(
            @PathVariable String id,
            @Body SendMessageRequest msg) {
        chatMessageManager.sendMessage(id, msg.getContent());
        return HttpResponse.ok();
    }

    /**
     * Get chat messages for partner
     *
     * @param id {@link UUID} the partner id
     * @return HTTP status
     */
    @Get("/{id}/messages")
    public HttpResponse<List<ChatMessage>> getMessagesForPartner(
            @PathVariable String id) {
        return HttpResponse.ok(chatMessageService.getMessagesForPartner(id));
    }

}
