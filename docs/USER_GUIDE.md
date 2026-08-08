# MineSplat user guide / Руководство MineSplat

## Русский

### Быстрая установка через Prism Launcher

1. Установите актуальный [Prism Launcher](https://prismlauncher.org/) и добавьте
   Microsoft-аккаунт, на котором куплен Minecraft Java Edition.
2. В Prism нажмите `Add Instance → Import`.
3. Выберите `minesplat-prism-1.21.1-0.2.0.mrpack`.
4. Откройте настройки инстанса и проверьте, что выбрана Java 21. На Apple Silicon
   нужна ARM64/AArch64 Java; на Intel Mac — x64 Java.
5. Выделите инстансу 4–6 GiB RAM.
6. Запустите его один раз и создайте или откройте мир.
7. Запустите свой TripoSplat API.
8. На Mac проверьте сервер:

   ```bash
   curl http://127.0.0.1:8080/health
   ```

   Для удалённого сервера замените адрес своим полным HTTP/HTTPS base URL.
9. В игре нажмите `M + G`, вставьте этот base URL и нажмите «Проверить API».
10. Перетащите PNG/JPEG в окно или выберите файл, задайте имя, seed и разрешение,
    затем нажмите «Создать схему».

Готовый файл окажется в
`<папка инстанса>/minecraft/schematics/minesplat/` и сразу появится перед
игроком как выбранный placement Litematica. Для движения и вращения используйте
обычные инструменты Litematica.

Окно можно закрывать во время работы — задача продолжится. При временном обрыве
сети MineSplat делает повторы через 1, 2 и 4 секунды, затем предлагает
«Продолжить опрос». Запущенную GPU-задачу API v1 отменить не умеет; задачу в
очереди можно отменить.

Смена 32/64/128/256/512/1024 в текущей сессии повторяет только вокселизацию
существующего PLY. Смена палитры или blacklist при том же разрешении
пересобирает схему локально. Смена seed или изображения создаёт новую
Gaussian-модель. `1024³` совпадает с максимальным разрешением TripoSplat API,
но это экспериментально тяжёлый режим: рекомендуется выделить инстансу минимум
12–16 ГиБ памяти. TSVOX, подбор цветов и Litematica-контейнер могут занимать
сотни мегабайт, а серверная вокселизация выполняться существенно дольше.

### Опциональная интеграция Chisels & Bits

Базовый `.mrpack` намеренно не содержит Chisels & Bits. Обычный экспорт
Litematica работает без него.

1. В Prism откройте `Edit Instance → Mods → Download Mods`.
2. Найдите Chisels & Bits и установите точную Fabric-версию `21.1.33`.
   Подтвердите зависимости, которые предложит Prism.
3. Запустите singleplayer-мир и переключитесь в Creative.
4. В MineSplat выберите
   `Результат → Миниатюра Chisels & Bits` и создайте blueprint.
5. Нажмите «Разместить миниатюру». Появится цветное полупрозрачное превью:
   - `R` / `Shift+R` — поворот на 90°;
   - стрелки — сдвиг по X/Z;
   - `Page Up` / `Page Down` — сдвиг по Y;
   - правая кнопка мыши — подтверждение;
   - `Esc` — отмена.

Все клавиши, кроме подтверждения мышью, можно изменить в настройках Controls.
Размещение блокируется, если миниатюра пересекается с любым существующим блоком.
MineSplat ничего не перезаписывает и откатывает созданные им C&B-блоки при
ошибке.

Blueprint-файлы сохраняются в
`<папка инстанса>/minecraft/minesplat/blueprints/` и доступны через кнопку
«Сохранённые миниатюры» после перезапуска. Один TSVOX-воксель становится одним
C&B bit; при стандартной сетке 16×16×16 resolution 32/64/128/256/512/1024
занимает максимум 2/4/8/16/32/64 блоков по каждой оси.

Создавать `.msbp` можно в любом открытом мире, но размещать их в версии 0.2.0
можно только в singleplayer Creative. Litematica для C&B-экспорта не
используется.

### Ручная установка

1. В Prism нажмите `Add Instance → Custom`.
2. Выберите Minecraft `1.21.1` и Fabric Loader `0.16.7`.
3. В `Edit → Mods → Download Mods` установите точные версии:
   - Fabric API `0.107.0+1.21.1`;
   - Litematica `0.19.50`;
   - MaLiLib `0.21.0`;
   - Mod Menu `11.0.3`.
4. Через `Add File` добавьте
   `minesplat-fabric-1.21.1-0.2.0.jar`.
5. Выберите Java 21, выделите 4–6 GiB памяти и выполните шаги 6–10 выше.

MineSplat, Litematica и MaLiLib устанавливаются только в клиент. Они не нужны на
Minecraft-сервере. MineSplat не запускает TripoSplat API, не настраивает TLS и
не добавляет авторизацию — это ответственность владельца API.

### Для финальной end-to-end проверки

Разработчику понадобятся:

- base URL работающего TripoSplat API v1, доступный из Minecraft на Mac;
- одна контрольная PNG/JPEG-картинка;
- желательно ещё две: яркий объект и объект с тонкими деталями.

До завершения MVP контракт API v1 и формат TSVOX v2 должны оставаться
совместимыми.

## English

### Quick Prism Launcher install

1. Install a current [Prism Launcher](https://prismlauncher.org/) and add the
   Microsoft account that owns Minecraft Java Edition.
2. Select `Add Instance → Import`.
3. Choose `minesplat-prism-1.21.1-0.2.0.mrpack`.
4. Verify the instance uses Java 21. Use ARM64/AArch64 Java on Apple Silicon and
   x64 Java on an Intel Mac.
5. Allocate 4–6 GiB RAM.
6. Launch once and create or open a world.
7. Start your TripoSplat API.
8. Verify it from macOS with `curl <BASE_URL>/health`.
9. Press `M + G` in Minecraft, enter the same base URL, and select `Test API`.
10. Drop or choose a PNG/JPEG, select the seed and voxel preset, and create the
    schematic.

The `.litematic` is saved under
`<instance>/minecraft/schematics/minesplat/`, loaded by Litematica, and placed
in front of the player. Closing the MineSplat screen does not stop a task.

### Optional Chisels & Bits integration

The base `.mrpack` deliberately does not bundle Chisels & Bits, and normal
Litematica export works without it.

1. Open `Edit Instance → Mods → Download Mods` in Prism.
2. Install the exact Fabric release Chisels & Bits `21.1.33` and accept the
   dependencies suggested by Prism.
3. Open a singleplayer world in Creative mode.
4. Select `Output → Chisels & Bits miniature`, generate the blueprint, and
   select `Place miniature`.
5. Position the colored hologram with `R` / `Shift+R`, the arrow keys, and
   Page Up/Down. Right-click confirms; Escape cancels.

Saved `.msbp` files are kept under
`<instance>/minecraft/minesplat/blueprints/` and can be reopened from `Saved
miniatures` after a restart. One TSVOX voxel maps to one C&B bit. Resolutions
32/64/128/256/512/1024 occupy at most 2/4/8/16/32/64 host blocks per side with
the standard 16-bit grid. `1024³` matches the TripoSplat API maximum, is
exceptionally heavy, and benefits from at least 12–16 GiB allocated to the
instance. Placement requires every occupied host block to be air and is
supported only in Creative singleplayer in MineSplat 0.2.0. This output path
does not create or place a Litematica schematic.

### Manual install

Create a Minecraft 1.21.1 instance with Fabric Loader 0.16.7. Install Fabric API
0.107.0+1.21.1, Litematica 0.19.50, MaLiLib 0.21.0, and Mod Menu 11.0.3, then
add the MineSplat JAR. Use Java 21 and allocate 4–6 GiB RAM.

MineSplat is client-only. No MineSplat, Litematica, or MaLiLib installation is
needed on a Minecraft server.

Prism documentation:
[creating instances](https://prismlauncher.org/wiki/getting-started/create-instance/)
and [managing loader mods](https://prismlauncher.org/wiki/help-pages/loader-mods/).
