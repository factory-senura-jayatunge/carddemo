package org.carddemo.identity.web;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.web.Dtos.Features;
import org.carddemo.identity.web.Dtos.Modules;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the web app shows: installed optional modules, converted units and its own switches. */
@RestController
@Tag(name = "features")
@ApiResponse(responseCode = "4XX", description = "No session",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class FeaturesController {

    private final IdentityProperties props;
    private final IdentityFidelity fidelity;

    FeaturesController(IdentityProperties props, IdentityFidelity fidelity) {
        this.props = props;
        this.fidelity = fidelity;
    }

    @GetMapping("/api/features")
    @Operation(operationId = "features", summary = "Installed modules, converted units and UI switches")
    @ApiResponse(responseCode = "200", description = "What the web app shows")
    Features features() {
        IdentityProperties.Features f = props.features();
        return new Features(new Modules(f.authorization(), f.transactionTypes()), List.copyOf(f.converted()),
                Map.of(IdentitySwitches.DEF_BILL_014.itemId(), fidelity.billPayAccountLock().key()));
    }
}
