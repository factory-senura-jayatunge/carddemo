package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;

@Schema(name = "CustomerView")
public record CustomerView(
        @Schema(examples = "000000001") String custId,
        @Schema(implementation = String.class, description = "Masked, display form nnn-nn-nnnn (D-09).",
                examples = "***-**-3888") Pii ssn,
        @Schema(implementation = String.class, description = "Masked; \"\" when not stored (D-09).",
                examples = "****-06-08") Pii dob,
        @Schema(examples = "274") String ficoScore,
        String firstName,
        String middleName,
        String lastName,
        String addrLine1,
        String addrLine2,
        @Schema(description = "addr_line_3.") String city,
        String stateCd,
        @Schema(description = "Stored value, up to 10 characters (D-14).") String zip,
        String countryCd,
        @Schema(description = "Stored value, up to 15 characters (D-14).", examples = "(908)119-8310") String phone1,
        String phone2,
        @Schema(implementation = String.class, description = "Masked (D-09).") Pii govtIssuedId,
        @Schema(implementation = String.class, description = "Masked (D-09).") Pii eftAccountId,
        String priCardHolderInd) {
}
