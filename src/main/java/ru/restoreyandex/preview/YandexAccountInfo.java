package ru.restoreyandex.preview;

/**
 * Отображаемые сведения об уже авторизованном аккаунте Яндекс Диска.
 *
 * <p>Сведения не содержат пароль, cookie или OAuth-токен. Email может быть
 * пустым, когда вход подтверждён, но интерфейс Яндекса не позволил прочитать
 * карточку профиля.</p>
 */
public record YandexAccountInfo(String email, String displayName) {
    /** Создаёт сведения и заменяет отсутствующие значения пустыми строками. */
    public YandexAccountInfo {
        email = email == null ? "" : email.strip();
        displayName = displayName == null ? "" : displayName.strip();
    }

    /** Возвращает подтверждённый вход без доступных отображаемых сведений. */
    public static YandexAccountInfo authenticatedWithoutProfileDetails() {
        return new YandexAccountInfo("", "");
    }

    /** Проверяет, удалось ли прочитать email аккаунта. */
    public boolean hasEmail() {
        return !email.isEmpty();
    }

    /** Проверяет, удалось ли прочитать отображаемое имя аккаунта. */
    public boolean hasDisplayName() {
        return !displayName.isEmpty();
    }
}
