package app.resourcehub.shared.business.structs;

import java.util.Currency;
import java.util.regex.Pattern;

public record MoneyMinor(long amountMinor, String currencyCode) {

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    public MoneyMinor {
        if (amountMinor < 0L) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "amountMinor must not be negative");
        }
        if (currencyCode == null || !ISO_CURRENCY_PATTERN.matcher(currencyCode).matches()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "currencyCode must be a 3-letter uppercase ISO-4217 code");
        }
        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException ex) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unknown ISO-4217 currency code: " + currencyCode);
        }
    }

    public MoneyMinor add(MoneyMinor other) {
        requireSameCurrency(other);
        try {
            return new MoneyMinor(Math.addExact(amountMinor, other.amountMinor), currencyCode);
        } catch (ArithmeticException ex) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "Monetary sum overflow");
        }
    }

    public MoneyMinor subtract(MoneyMinor other) {
        requireSameCurrency(other);
        if (amountMinor < other.amountMinor) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Monetary subtraction cannot yield negative amount");
        }
        return new MoneyMinor(amountMinor - other.amountMinor, currencyCode);
    }

    public MoneyMinor multiply(long factor) {
        if (factor < 0L) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "factor must not be negative");
        }
        try {
            return new MoneyMinor(Math.multiplyExact(amountMinor, factor), currencyCode);
        } catch (ArithmeticException ex) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "Monetary product overflow");
        }
    }

    private void requireSameCurrency(MoneyMinor other) {
        if (other == null || !currencyCode.equals(other.currencyCode)) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Monetary operation requires matching currency codes");
        }
    }
}
