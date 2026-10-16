package ru.restoreyandex.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет извлечение отображаемого имени из текста карточки аккаунта Яндекс ID. */
class YandexAccountNameParserTest {
    @Test
    void findsNameImmediatelyBeforeEmailInAccountCard() {
        String accountCard = "Сергей Иванов\n"
                + "+7 925 ***-**-15\n"
                + "user@example.org\n"
                + "Тарифы Яндекс 360";

        assertEquals(new YandexAccountInfo("user@example.org", "Сергей Иванов"),
                YandexAccountNameParser.findAccount(accountCard).orElseThrow());
    }

    @Test
    void ignoresPhoneAndServiceLabelsBeforeEmail() {
        String accountCard = "Аккаунт\n"
                + "+7 999 111-22-33\n"
                + "Почта\n"
                + "user@example.org";

        assertEquals(new YandexAccountInfo("user@example.org", ""),
                YandexAccountNameParser.findAccount(accountCard).orElseThrow());
    }

    @Test
    void returnsEmptyWhenAccountCardDoesNotContainEmail() {
        assertEquals(java.util.Optional.empty(), YandexAccountNameParser.findAccount("Файлы\nФото\nАльбомы"));
    }
}
