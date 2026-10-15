package ru.restoreyandex.preview;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import ru.restoreyandex.logging.ApplicationLogger;

/**
 * Извлекает настоящую миниатюру файла с помощью Windows Shell.
 *
 * <p>Класс нужен только для файлов, полный контент которых недоступен или
 * повреждён. Он не пытается декодировать исходный файл самостоятельно и не
 * копирует его: вместо этого обращается к тому же Shell API, которым пользуется
 * Проводник Windows для плиток и эскизов.</p>
 *
 * <p>Последовательность работы для одного файла:</p>
 * <ol>
 *     <li>проверяет, что процесс запущен в Windows;</li>
 *     <li>инициализирует COM в текущем рабочем потоке;</li>
 *     <li>создаёт Shell-объект для абсолютного пути через
 *         {@code SHCreateItemFromParsingName};</li>
 *     <li>сначала запрашивает у {@code IShellItemImageFactory} объект
 *         {@code HBITMAP} до 2048×2048 пикселей, затем при отсутствии такого
 *         кэша — запасной вариант до 512×512 пикселей;</li>
 *     <li>передаёт пиксели {@code HBITMAP} в {@link BufferedImage}, который
 *         затем сохраняет сервис восстановления.</li>
 * </ol>
 *
 * <p>Флаг {@code THUMBNAILONLY} принципиален: Shell не имеет права подменить
 * миниатюру стандартной иконкой формата файла. Поэтому в резервную копию не
 * попадёт ярлык, иконка JPG или иной ложный результат. Если миниатюра уже есть
 * в кэше Проводника, Windows отдаёт её. Иначе установленный провайдер Яндекс
 * Диска может вернуть своё облачное превью. Дополнительный флаг
 * {@code INCACHEONLY} не разрешает Shell ждать загрузки полного файла: берётся
 * только уже доступная миниатюра. Когда её нет, метод возвращает пустой
 * результат, а вызывающий код фиксирует
 * {@code NO_FILE}.</p>
 *
 * <p>Владение нативными ресурсами остаётся в этом классе: после преобразования
 * {@code HBITMAP} всегда освобождается вызовом {@code DeleteObject}, COM-объект
 * освобождается вызовом {@code Release}, а COM-поток — {@code CoUninitialize}.
 * Любая ошибка Shell API, конвертации пикселей или COM возвращается как
 * {@link Optional#empty()}; ошибка одного превью не должна останавливать
 * многодневное восстановление архива.</p>
 */
public final class WindowsShellPreviewExtractor implements PreviewExtractor {
    private static final String IMAGE_FACTORY_IID = "{BCC18B79-BA16-442F-80C4-8A59C30C463B}";
    private static final int THUMBNAIL_ONLY = 0x8;
    private static final int IN_CACHE_ONLY = 0x10;
    private static final int BIGGER_SIZE_OK = 0x1;
    private static final int HIGH_RESOLUTION_SIZE = 2048;
    private static final int FALLBACK_SIZE = 512;
    private static final int DIB_RGB_COLORS = 0;
    private static final int BI_RGB = 0;

    /**
     * Пытается получить миниатюру для одного пути.
     *
     * @param file исходный файл или облачная заглушка
     * @return миниатюра в памяти либо пустой результат, если Shell её не отдал
     */
    @Override
    public Optional<BufferedImage> extract(Path file) {
        if (!isWindows()) {
            return Optional.empty();
        }
        int initialization = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue();
        if (initialization < 0) {
            return Optional.empty();
        }
        Pointer shellItem = null;
        try {
            PointerByReference reference = new PointerByReference();
            int result = Shell32Extra.INSTANCE.SHCreateItemFromParsingName(
                    new WString(file.toAbsolutePath().toString()), null,
                    new Guid.REFIID(new Guid.IID(IMAGE_FACTORY_IID)), reference).intValue();
            if (result < 0 || reference.getValue() == null) {
                return Optional.empty();
            }
            shellItem = reference.getValue();
            Optional<WinDef.HBITMAP> bitmap = getBestAvailableBitmap(shellItem);
            if (bitmap.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(toBufferedImage(bitmap.get()));
            } finally {
                GDI32.INSTANCE.DeleteObject(bitmap.get());
            }
        } catch (RuntimeException exception) {
            ApplicationLogger.error("Windows Shell не вернул превью: " + file, exception);
            return Optional.empty();
        } finally {
            if (shellItem != null) {
                invoke(shellItem, 2, shellItem);
            }
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    /**
     * Запрашивает максимальное уже закэшированное превью. При отсутствии
     * большой версии повторяет запрос для 512 пикселей, чтобы не терять
     * единственное доступное, но менее детальное изображение.
     */
    private Optional<WinDef.HBITMAP> getBestAvailableBitmap(Pointer shellItem) {
        Optional<WinDef.HBITMAP> highResolution = getBitmap(shellItem, HIGH_RESOLUTION_SIZE,
                THUMBNAIL_ONLY | IN_CACHE_ONLY | BIGGER_SIZE_OK);
        return highResolution.isPresent() ? highResolution
                : getBitmap(shellItem, FALLBACK_SIZE, THUMBNAIL_ONLY | IN_CACHE_ONLY);
    }

    /** Запрашивает миниатюру указанного размера без возврата иконки формата. */
    private Optional<WinDef.HBITMAP> getBitmap(Pointer shellItem, int size, int flags) {
        PointerByReference bitmapReference = new PointerByReference();
        int result = invoke(shellItem, 3, shellItem, new SizeByValue(size, size), flags, bitmapReference);
        if (result < 0 || bitmapReference.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(new WinDef.HBITMAP(bitmapReference.getValue()));
    }

    /**
     * Копирует пиксели {@code HBITMAP} в управляемую память Java.
     * Отрицательная высота DIB задаёт порядок строк сверху вниз, поэтому
     * изображение не переворачивается при передаче в {@link BufferedImage}.
     */
    private BufferedImage toBufferedImage(WinDef.HBITMAP bitmap) {
        WinGDI.BITMAP nativeBitmap = new WinGDI.BITMAP();
        int read = GDI32.INSTANCE.GetObject(bitmap, nativeBitmap.size(), nativeBitmap.getPointer());
        nativeBitmap.read();
        if (read == 0 || nativeBitmap.bmWidth.intValue() <= 0 || nativeBitmap.bmHeight.intValue() <= 0) {
            return null;
        }
        int width = nativeBitmap.bmWidth.intValue();
        int height = nativeBitmap.bmHeight.intValue();
        WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
        info.bmiHeader.biSize = info.bmiHeader.size();
        info.bmiHeader.biWidth = width;
        info.bmiHeader.biHeight = -height;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;
        info.write();
        Memory pixels = new Memory((long) width * height * Integer.BYTES);
        WinDef.HDC deviceContext = User32.INSTANCE.GetDC(null);
        try {
            int linesRead = GDI32.INSTANCE.GetDIBits(deviceContext, bitmap, 0, height, pixels, info, DIB_RGB_COLORS);
            if (linesRead != height) {
                return null;
            }
        } finally {
            User32.INSTANCE.ReleaseDC(null, deviceContext);
        }
        int[] argb = pixels.getIntArray(0, width * height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    /**
     * Вызывает метод COM-интерфейса по индексу в его vtable.
     * Индекс 2 соответствует {@code IUnknown::Release}, а индекс 3 —
     * {@code IShellItemImageFactory::GetImage}.
     */
    private static int invoke(Pointer instance, int slot, Object... arguments) {
        Pointer vtable = instance.getPointer(0);
        Pointer address = vtable.getPointer((long) slot * Native.POINTER_SIZE);
        return Function.getFunction(address, Function.ALT_CONVENTION).invokeInt(arguments);
    }

    /** Возвращает {@code true} только для Windows, где доступен Windows Shell API. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Нативное объявление, отсутствующее в стандартном отображении JNA.
     * Функция создаёт COM-объект сразу нужного интерфейса, чтобы не выполнять
     * отдельный {@code QueryInterface} вручную.
     */
    private interface Shell32Extra extends StdCallLibrary {
        Shell32Extra INSTANCE = Native.load("shell32", Shell32Extra.class);

        WinNT.HRESULT SHCreateItemFromParsingName(WString path, Pointer bindContext,
                                                  Guid.REFIID requestedInterface,
                                                  PointerByReference shellItem);
    }

    /** Структура Win32 SIZE, передаваемая в COM-метод по значению. */
    public static final class SizeByValue extends Structure implements Structure.ByValue {
        public int cx;
        public int cy;

        /** Создаёт требуемую ширину и высоту миниатюры в пикселях. */
        public SizeByValue(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cx", "cy");
        }
    }
}
