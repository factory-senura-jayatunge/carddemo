package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The customer of the account's lowest card (D-61); no protected column is read. */
@Schema(name = "SummaryCustomer")
public record SummaryCustomer(
        @Schema(examples = "000000001") String custId,
        @Schema(description = "First word of the first name, the middle initial and the first word of the last"
                + " name, one space apart; a blank middle name leaves three spaces. Not cut to a width.",
                examples = "Immanuel M Kessler") String name,
        @Schema(description = "Address lines 1 and 2 joined by \",\", each without its trailing spaces.",
                examples = "618 Deshaun Route,Apt. 802") String addressLine1,
        @Schema(description = "Address line 3, state and the first five characters of the ZIP, joined by \",\", each"
                + " without its trailing spaces.", examples = "Altenwerthshire,NC,12546")
        String addressLine2,
        @Schema(description = "Phone 1 as stored, up to 15 characters.", examples = "(908)119-8310") String phone) {
}
