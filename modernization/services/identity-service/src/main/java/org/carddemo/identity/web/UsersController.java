package org.carddemo.identity.web;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.carddemo.identity.user.UserAdminService;
import org.carddemo.identity.user.UserAdminService.NewUser;
import org.carddemo.identity.user.UserAdminService.UserChange;
import org.carddemo.identity.user.UserAdminService.UserList;
import org.carddemo.identity.user.UserMessages;
import org.carddemo.identity.user.UserView;
import org.carddemo.identity.web.Dtos.NewUserRequest;
import org.carddemo.identity.web.Dtos.Notice;
import org.carddemo.identity.web.Dtos.SavedUser;
import org.carddemo.identity.web.Dtos.UpdateUserRequest;
import org.carddemo.identity.web.Dtos.UserPage;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User administration (USRL, USRA, USRU, USRD); role ADMIN only (SecurityConfig). */
@RestController
@RequestMapping("/api/users")
@Tag(name = "users")
class UsersController {

    private static final String PROBLEM = "application/problem+json";
    private static final String KEY = "The user ID in key form: base64url (RFC 4648 section 5, no padding) of its UTF-8 bytes";

    private final UserAdminService users;

    UsersController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listUsers", summary = "One page of active users in user ID order (COUSR00C)")
    @ApiResponse(responseCode = "200", description = "The page, with the message line when there is one")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (a bad startKey), UNSUPPORTED_CHARACTER, TOO_LONG, INVALID_PAGE_REQUEST, INVALID_PAGE_SIZE or INVALID_CURSOR",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    UserPage list(
            @Parameter(description = "Case-insensitive user ID prefix, as typed") @RequestParam(required = false) String start,
            @Parameter(description = "The same prefix in key form: base64url of its UTF-8 bytes, no padding")
            @RequestParam(required = false) String startKey,
            @Parameter(description = "lastKey of the previous page: the next page") @RequestParam(required = false) String after,
            @Parameter(description = "firstKey of the current page: the previous page") @RequestParam(required = false) String before,
            @Parameter(description = "Rows, 1 to 10", schema = @Schema(type = "integer", minimum = "1", maximum = "10", defaultValue = "10"))
            @RequestParam(required = false) String size) {
        if (present(startKey) + present(start) + present(after) + present(before) > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_REQUEST, null);
        }
        String search = present(startKey) == 1 ? UserKeys.decode(startKey) : start;
        UserList list = users.list(search, after, before, size);
        Message m = list.message();
        return new UserPage(list.page().items(), list.page().hasNext(), list.page().hasPrevious(),
                list.page().firstKey(), list.page().lastKey(), m == null ? null : m.render(),
                m == null ? null : m.severity().key());
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addUser", summary = "Add a user (COUSR01C)")
    @ApiResponse(responseCode = "201", description = "Added",
            headers = {@Header(name = HttpHeaders.LOCATION, schema = @Schema(type = "string")),
                    @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST, a *_EMPTY text, UNSUPPORTED_CHARACTER, TOO_LONG, USER_ID_INVALID, NEW_PASSWORD_LENGTH, NEW_PASSWORD_IS_USER_ID or USER_TYPE_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "USER_ID_EXISTS",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_ADD_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "BATCH_WINDOW, with Retry-After",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    ResponseEntity<SavedUser> add(@AuthenticationPrincipal Jwt jwt, @RequestBody NewUserRequest body) {
        UserView user = users.add(jwt.getSubject(), new NewUser(body.firstName(), body.lastName(), body.userId(),
                body.password(), body.userType()));
        URI location = URI.create("/api/users/key/" + UserKeys.encode(user.userId()));
        return ResponseEntity.created(location).eTag(user.etag())
                .body(saved(user, UserMessages.USER_ADDED));
    }

    @GetMapping(path = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getUser", summary = "Fetch a user for update or delete (COUSR02C, COUSR03C)")
    @ApiResponse(responseCode = "200", description = "The user",
            headers = @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "USER_ID_EMPTY, UNSUPPORTED_CHARACTER, TOO_LONG or USER_ID_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    ResponseEntity<UserView> get(@PathVariable String userId) {
        UserView user = users.fetch(userId);
        return ResponseEntity.ok().eTag(user.etag()).body(user);
    }

    @GetMapping(path = "/key/{userKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getUserByKey", summary = "getUser with the user ID in key form, for any user ID")
    @ApiResponse(responseCode = "200", description = "The user",
            headers = @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (a bad key), USER_ID_EMPTY, UNSUPPORTED_CHARACTER, TOO_LONG or USER_ID_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    ResponseEntity<UserView> getByKey(@Parameter(description = KEY) @PathVariable String userKey) {
        return get(UserKeys.decode(userKey));
    }

    @PutMapping(path = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateUser", summary = "Save a user's names, type and optionally a new password (COUSR02C)")
    @ApiResponse(responseCode = "200", description = "Saved",
            headers = @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST, INVALID_IF_MATCH, a *_EMPTY text, UNSUPPORTED_CHARACTER, TOO_LONG, USER_ID_INVALID, NEW_PASSWORD_LENGTH, NEW_PASSWORD_IS_USER_ID, NEW_PASSWORD_SAME, USER_TYPE_INVALID or NO_CHANGE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "RECORD_CHANGED, with current and its ETag",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED or USER_UPDATE_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "BATCH_WINDOW, with Retry-After",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    ResponseEntity<SavedUser> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
            @Parameter(description = "The version of the user the page showed, as its ETag")
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateUserRequest body) {
        UserView user = users.update(jwt.getSubject(), userId, ifMatch,
                new UserChange(body.firstName(), body.lastName(), body.userType(), body.password()));
        return ResponseEntity.ok().eTag(user.etag()).body(saved(user, UserMessages.USER_UPDATED));
    }

    @PutMapping(path = "/key/{userKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateUserByKey", summary = "updateUser with the user ID in key form, for any user ID")
    @ApiResponse(responseCode = "200", description = "Saved",
            headers = @Header(name = HttpHeaders.ETAG, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (a bad key or body), INVALID_IF_MATCH, a *_EMPTY text, UNSUPPORTED_CHARACTER, TOO_LONG, USER_ID_INVALID, NEW_PASSWORD_LENGTH, NEW_PASSWORD_IS_USER_ID, NEW_PASSWORD_SAME, USER_TYPE_INVALID or NO_CHANGE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "RECORD_CHANGED, with current and its ETag",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED or USER_UPDATE_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "BATCH_WINDOW, with Retry-After",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    ResponseEntity<SavedUser> updateByKey(@AuthenticationPrincipal Jwt jwt, @Parameter(description = KEY) @PathVariable String userKey,
            @Parameter(description = "The version of the user the page showed, as its ETag")
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateUserRequest body) {
        return update(jwt, UserKeys.decode(userKey), ifMatch, body);
    }

    @DeleteMapping(path = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deleteUser", summary = "Delete a user, keeping its row and ID (COUSR03C, D-56)")
    @ApiResponse(responseCode = "200", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "INVALID_IF_MATCH or a user ID rule",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "CANNOT_DELETE_SELF, LAST_ADMIN or RECORD_CHANGED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED or USER_DELETE_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "BATCH_WINDOW, with Retry-After",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    Notice delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
            @Parameter(description = "The version of the user the page showed, as its ETag")
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        String deleted = users.delete(jwt.getSubject(), userId, ifMatch);
        return new Notice(UserMessages.USER_DELETED.render(deleted), UserMessages.USER_DELETED.severity().key());
    }

    @DeleteMapping(path = "/key/{userKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deleteUserByKey", summary = "deleteUser with the user ID in key form, for any user ID")
    @ApiResponse(responseCode = "200", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (a bad key), INVALID_IF_MATCH or a user ID rule",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "USER_ID_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "CANNOT_DELETE_SELF, LAST_ADMIN or RECORD_CHANGED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "500", description = "USER_LOOKUP_FAILED or USER_DELETE_FAILED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "BATCH_WINDOW, with Retry-After",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    Notice deleteByKey(@AuthenticationPrincipal Jwt jwt, @Parameter(description = KEY) @PathVariable String userKey,
            @Parameter(description = "The version of the user the page showed, as its ETag")
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return delete(jwt, UserKeys.decode(userKey), ifMatch);
    }

    private static int present(String s) {
        return s == null || s.isBlank() ? 0 : 1;
    }

    private static SavedUser saved(UserView user, Message message) {
        return new SavedUser(user, message.render(user.userId()), message.severity().key());
    }
}
