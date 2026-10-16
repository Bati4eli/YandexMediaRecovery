package ru.restoreyandex.preview;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Извлекает отображаемое имя владельца из текста открытой карточки Яндекс ID.
 *
 * <p>Яндекс Диск не предоставляет для этого стабильный публичный DOM API,
 * поэтому класс намеренно не привязан к CSS-именам страницы. Карточка профиля
 * содержит email и отображаемое имя непосредственно перед ним; служебные
 * строки и номер телефона при этом исключаются.</p>
 */
final class YandexAccountNameParser {
    private static final Pattern EMAIL = Pattern.compile("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^[+()0-9* .-]+$");
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");
    private static final Set<String> SERVICE_LABELS = Set.of(
            "аккаунт", "почта", "профиль", "настройки", "тарифы яндекс 360",
            "управление тарифом", "справка", "поддержка", "добавить аккаунт", "выйти");

    private YandexAccountNameParser() {
    }

    /**
     * Возвращает email и имя, расположенное перед ним в карточке аккаунта.
     *
     * @param accountCardText видимый текст страницы после открытия карточки
     * @return сведения об аккаунте либо пустой результат, если email не найден
     */
    static Optional<YandexAccountInfo> findAccount(String accountCardText) {
        if (accountCardText == null || accountCardText.isBlank()) {
            return Optional.empty();
        }
        String[] lines = accountCardText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toArray(String[]::new);
        for (int emailIndex = 0; emailIndex < lines.length; emailIndex++) {
            if (!EMAIL.matcher(lines[emailIndex]).matches()) {
                continue;
            }
            return Optional.of(new YandexAccountInfo(lines[emailIndex], candidateBeforeEmail(lines, emailIndex)));
        }
        return Optional.empty();
    }

    private static String candidateBeforeEmail(String[] lines, int emailIndex) {
        int firstLine = Math.max(0, emailIndex - 4);
        for (int index = emailIndex - 1; index >= firstLine; index--) {
            String candidate = lines[index];
            if (PHONE.matcher(candidate).matches() || isServiceLabel(candidate)) {
                continue;
            }
            if (HAS_LETTER.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean isServiceLabel(String candidate) {
        return SERVICE_LABELS.contains(candidate.toLowerCase(Locale.ROOT));
    }
}
