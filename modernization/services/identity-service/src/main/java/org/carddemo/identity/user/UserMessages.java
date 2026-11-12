package org.carddemo.identity.user;

import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;

/**
 * The texts of the user-admin units (COUSR00C to COUSR03C), keyed as in the identity-service
 * contract §7. The shared {@link MessageCatalog} holds them, so the web app renders the same texts.
 */
public final class UserMessages {

    public static final Message FIRST_NAME_EMPTY = MessageCatalog.USER_FIRST_NAME_EMPTY;
    public static final Message LAST_NAME_EMPTY = MessageCatalog.USER_LAST_NAME_EMPTY;
    public static final Message USER_ID_EMPTY = MessageCatalog.USER_ID_EMPTY;
    public static final Message PASSWORD_EMPTY = MessageCatalog.USER_PASSWORD_EMPTY;
    public static final Message USER_TYPE_EMPTY = MessageCatalog.USER_TYPE_EMPTY;
    public static final Message USER_ADDED = MessageCatalog.USER_ADDED;
    public static final Message USER_ID_EXISTS = MessageCatalog.USER_ID_EXISTS;
    public static final Message USER_ADD_FAILED = MessageCatalog.USER_ADD_FAILED;
    public static final Message USER_ID_NOT_FOUND = MessageCatalog.USER_ID_NOT_FOUND;
    public static final Message USER_LOOKUP_FAILED = MessageCatalog.USER_LOOKUP_FAILED;
    public static final Message USER_UPDATE_PROMPT = MessageCatalog.USER_UPDATE_PROMPT;
    public static final Message USER_NO_CHANGE = MessageCatalog.USER_NO_CHANGE;
    public static final Message USER_UPDATED = MessageCatalog.USER_UPDATED;
    public static final Message USER_UPDATE_FAILED = MessageCatalog.USER_UPDATE_FAILED;
    public static final Message USER_DELETE_FAILED = MessageCatalog.USER_DELETE_FAILED;
    public static final Message USER_DELETE_PROMPT = MessageCatalog.USER_DELETE_PROMPT;
    public static final Message USER_DELETED = MessageCatalog.USER_DELETED;
    public static final Message USER_LIST_BOTTOM = MessageCatalog.USER_LIST_BOTTOM;
    public static final Message USER_LIST_TOP = MessageCatalog.USER_LIST_TOP;
    public static final Message USER_LIST_NONE = MessageCatalog.USER_LIST_NONE;
    public static final Message USER_LIST_ALREADY_TOP = MessageCatalog.USER_LIST_ALREADY_TOP;
    public static final Message USER_LIST_ALREADY_BOTTOM = MessageCatalog.USER_LIST_ALREADY_BOTTOM;
    public static final Message USER_INVALID_SELECTION = MessageCatalog.USER_INVALID_SELECTION;
    public static final Message USER_ID_INVALID = MessageCatalog.USER_ID_INVALID;
    public static final Message USER_TYPE_INVALID = MessageCatalog.USER_TYPE_INVALID;
    public static final Message CANNOT_DELETE_SELF = MessageCatalog.USER_CANNOT_DELETE_SELF;
    public static final Message LAST_ADMIN = MessageCatalog.USER_LAST_ADMIN;
    public static final Message USER_ADMIN_BATCH_WINDOW = MessageCatalog.USER_ADMIN_BATCH_WINDOW;
    public static final Message UNSUPPORTED_CHARACTER = MessageCatalog.USER_UNSUPPORTED_CHARACTER;
    public static final Message TOO_LONG = MessageCatalog.USER_TOO_LONG;

    private UserMessages() {
    }
}
