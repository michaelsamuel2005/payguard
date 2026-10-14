package io.github.michaelsamuel2005.payguard.domain;

/**
 * The single warning a customer sees. When several apply, the first in this order wins, because the most specific
 * warning is the one most likely to stop a scam. To release a warned payment, the customer must send back this
 * code, which proves the client showed them this warning rather than a generic one.
 */
public enum WarningCode {
    SCAM_LANGUAGE("Scammers ask people to move money to a 'safe account', to pay 'HMRC' or to invest in crypto. "
            + "Your bank and HMRC will never ask you to move money like this. If someone has asked you to, stop."),
    NAME_MISMATCH("The name you entered does not match the name on this account. If you were told to pay this "
            + "account, stop and check with the person directly, using contact details you already trust."),
    NAME_CHECK_UNAVAILABLE("We could not check the name on this account just now. Only continue if you are sure "
            + "who you are paying."),
    RAPID_NEW_PAYEES("You have paid several new people in a short time. If someone is rushing you, stop: "
            + "scammers create urgency."),
    LARGE_NEW_PAYEE("This is a large payment to someone you have not paid before. Check the request through a "
            + "contact you already trust before you send it."),
    NAME_CLOSE_MATCH("The name you entered is close to the name on this account, but not the same. Check it is "
            + "who you meant to pay."),
    UNUSUAL_AMOUNT("This payment is much larger than your usual payments. Make sure you meant to send this amount."),
    NEW_PAYEE("This is your first payment to this payee. Make sure the bank details came from a source you trust.");

    private final String message;

    WarningCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public static WarningCode parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("unknown warning code: " + code);
        }
    }
}
