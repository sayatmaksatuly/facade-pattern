# Защита Assignment 4 — Smart Parking Garage

Цель защиты — объяснить, почему система сохраняет корректное состояние после частичного сбоя, и показать это в работающем приложении. Не заучивайте определения отдельно от кода: откройте названные классы и проследите один запрос от CLI до SQLite и обратно.

## Что делает проект

Это подтверждение предоплаченного бронирования парковки. A проверяет номер и высоту машины, затем сохраняет разрешение на въезд `HELD`. B проверяет датчик и сохраняет место `RESERVED`. C рассчитывает стоимость и сохраняет платёж `CAPTURED`. После этого workflow получает `COMPLETED`.

`HELD` не означает, что машина уже физически въехала. Это действующее разрешение; `RESERVED` — выделенное место. Состояния `ADMITTED` и `OCCUPIED` показываются в самостоятельных операциях подсистем. Проект работает с локальным платёжным ledger, без реального банка или оборудования.

## Facade и границы подсистем

**Facade** предоставляет простой высокоуровневый интерфейс к нескольким подсистемам. Клиент вызывает `parking.park(request)`, а `ParkingFacade` знает порядок A → B → C, читает сохранённый progress и организует compensation. Подсистемы не теряют собственные public API.

**Adapter** решает другую задачу: преобразует несовместимый интерфейс. Например, Adapter мог бы перевести команды API производителя шлагбаума в интерфейс нашей системы. Здесь нет такого несовместимого API: нужно организовать несколько действий и скрыть эту координацию от клиента. Поэтому применён Facade.

| Подсистема | Классы | Что объяснить |
|---|---|---|
| A | `GateAdmission`, `LicensePlateReader`, `GateGateway`, `GateController` | Entity и legal transitions; чистое правило номера/высоты; собственный SQL gateway; самостоятельный API |
| B | `ParkingReservation`, `SpaceSensorGrid`, `ReservationGateway`, `ReservationManager` | Место 1–3; blocked/allocated; RESERVED, OCCUPIED, RELEASED; прямое сохранение своей модели |
| C | `ParkingPayment`, `OverstayAlert`, `PaymentGateway`, `PaymentTerminal` | 500 cents/час; 1–24 часа; sufficient tender; AUTHORIZED, CAPTURED, REFUNDED; overdue minutes |

Подсистемы не импортируют друг друга. Gate не знает о payment; payment не знает о reservation. Иначе их нельзя было бы независимо использовать и тестировать, а orchestration оказалась бы размазана по доменам. Их связывает только слой facades. Общий `Database` — техническая инфраструктура, он не содержит правил парковки.

Два facade разделяют пользовательские задачи. `ParkingFacade` подтверждает booking. `AdministrationFacade` читает сохранённые данные, показывает history, восстанавливает незавершённые workflow, выполняет manual cancellation и демонстрирует подсистемы. Его `recoverAll()` содержит цикл и обработку каждого запроса, а не является пустой обёрткой.

## Least Knowledge и SOLID

Principle of Least Knowledge: объект должен знать как можно меньше о внутренней структуре других объектов. CLI имеет непосредственные ссылки на facades. Он не получает gateway из controller и не ходит по цепочке объектов к JDBC connection. Конкретный before/after в README показывает три прямых вызова подсистем и замену на `parking.park(request)`.

`GarageRuntime` — composition root. Он знает конструкторы и связывает объекты один раз. Знание инфраструктуры в composition root оправдано; пользовательский обработчик бронирования этого знания не требует.

SRP видно в разделении entity / policy / gateway / facade / CLI. Constructor injection делает зависимости явными. Для нового правила сначала выбираем владельца бизнес-правила, затем меняем соответствующий тест. При единственной локальной реализации не добавлялись интерфейсы ради количества. Это практический компромисс: не утверждайте, что constructor injection автоматически доказывает DIP. При подключении реального оборудования интерфейс на внешней границе станет полезен. LSP и ISP не нужно искусственно демонстрировать наследованием, которого проект не требует.

## Persistence и ошибки

`Database.transaction` — одна SQLite transaction на операцию gateway. A завершает свой commit до B; B завершает commit до C. Одна общая transaction на весь workflow скрыла бы обязательную compensation. Здесь отмена — отдельное бизнес-действие с новым commit, а не rollback уже завершённой SQL transaction.

SQLite хранит entities, statuses, sensor readings, workflow request/progress и events. `WorkflowStore.transition()` меняет progress и добавляет соответствующий event атомарно. WAL и `synchronous=FULL` поддерживают durable commits. После выхода из процесса данные остаются в файле.

При B failure уже существует A. Facade сохраняет `COMPENSATING`, отменяет A и сохраняет `COMPENSATED`. У B и C нет committed записей для этого operation ID. Например, persisted sensor reading говорит, что место заблокировано.

При C failure уже существуют A и B. Недостаточная сумма отклоняется реальным правилом `OverstayAlert.requireSufficientTender`. Facade сначала освобождает B, затем отменяет A. В persisted journal и assertions идут keys `B_COMPENSATED`, затем `A_COMPENSATED`; CLI history выводит сообщения `Compensation B committed`, затем `Compensation A committed`. Записи остаются в SQLite со статусами `RELEASED` и `CANCELLED`.

Обратный порядок нужен, потому что поздний шаг использует результат раннего. Пока отмена B не подтверждена, нельзя объявлять всю исходную подготовку отменённой. Если B compensation падает, метод останавливается, A сохраняется, progress остаётся `COMPENSATING`. После исправления причины recovery повторяет действия по данным на диске.

Если C уже успел commit, но процесс упал до обновления journal, при ручной отмене нужно учитывать и C. Поэтому общий cancellation поддерживает C → B → A. Это не меняет обязательный сценарий C failure: при недостаточном tender C ничего не commit, значит compensation остаётся B → A.

Дополнительная ошибка — высота 230 cm при clearance 220 cm. Она возникает в A до domain commit. Workflow становится `REJECTED`. Неверный operation ID, номер, место, часы и числовой ввод тоже проверяются.

## Как работает restart recovery

`PENDING_A → PENDING_B → PENDING_C → COMPLETED` описывает следующий шаг. Ошибка после domain commit переводит workflow в `COMPENSATING`, успешная отмена — в `COMPENSATED`. `REJECTED` означает, что domain effects отсутствуют.

`ParkingFacade.resume(id)` заново читает `WorkflowStore.get(id)` в цикле. Он не основывается на локальных boolean вроде `aSucceeded`. Это принципиально: после закрытия JVM таких переменных уже нет.

Между commit подсистемы и записью progress есть возможное окно сбоя. Поэтому каждый шаг принимает тот же operation ID и проверяет существующие данные. Повтор идентичной операции безопасен; другой payload под тем же ID отклоняется. Entity transitions строгие, а gateway делает повтор отмены безопасным: если статус уже конечный, повторно переходить не нужно. Это разделение сохраняет строгую модель и позволяет idempotent recovery.

Если весь SQLite недоступен, нельзя обещать немедленное восстановление: сначала нужно восстановить доступ к файлу. Workflow остаётся доступным для следующей попытки, если его предыдущий commit был успешным. Демонстрация рассчитана на один процесс, работающий с одним файлом БД.

## Какие методы открыть на защите

| Файл или метод | Что показать |
|---|---|
| `facade/ParkingFacade.java`: `park`, `resume`, `advance` | Один запрос клиента, persisted decisions, последовательность A → B → C |
| `facade/ParkingFacade.java`: `handleFailure`, `compensate` | Проверка реальных записей и reverse order; остановка при неудачной compensation |
| `facade/AdministrationFacade.java`: `inspect`, `history`, `recoverAll` | Операционная поверхность без JDBC в CLI |
| `workflow/WorkflowStore.java`: `createOrLoad`, `transition`, `events` | Idempotency key, неизменность входных данных, atomic progress + event |
| `infrastructure/Database.java`: `transaction` | Локальная граница commit/rollback; отсутствие одной общей transaction на цепочку |
| `gate/GateAdmission.java`: `admit`, `cancel` | Legal transitions внутри entity |
| `spaces/ReservationGateway.java`: `reserve`, `release` | Sensor state и active allocation проверяются в persistent gateway operation |
| `payment/PaymentTerminal.java`: `charge`; `OverstayAlert` | Сначала проверка суммы, затем запись платёжного эффекта |
| `app/GarageRuntime.java` | Constructor injection и composition root |

Все пути в таблице относятся к `src/main/java/com/smartparking/`.

## Тесты и JaCoCo

F.I.R.S.T.:

- **Fast:** небольшие локальные SQLite файлы, нет сети и ожиданий внешних сервисов.
- **Independent:** каждый тест получает чистый временный persisted store, порядок запуска не важен.
- **Repeatable:** явные входные значения, одинаковые правила, отдельные БД.
- **Self-Validating:** JUnit assertions проверяют status, records и порядок events; человеку не нужно оценивать console output для pass/fail.
- **Timely:** production code и соответствующие тесты находятся в настоящих development commits. История не переписана для имитации TDD.

Откройте helpers в workflow tests: их имена выражают действие предметной области и помогают читать сценарий как предложение. Покажите отдельные тесты B failure, C failure и закрытия/повторного открытия store. Проверка SQLite важнее проверки только returned object.

JaCoCo показывает долю исполненных executable lines. 100% line coverage не гарантирует корректность всех ветвей, порядка компенсаций или бизнес-требований. Нужны содержательные assertions и failure scenarios. Реальные числа находятся в README и `target/site/jacoco/index.html`. Команда `.\mvnw.cmd clean verify` проверяет порог 80% отдельно для каждой подсистемы и обоих facade.

## Короткий сценарий живой демонстрации

1. Запустите `.\mvnw.cmd clean verify`. Покажите число тестов и HTML report JaCoCo. Откройте UML.
2. Запустите `.\mvnw.cmd -q compile exec:java "-Dexec.args=--db data/defense.db"` с новым именем БД.
3. Menu 1: `success-1`, `ABC123`, `180`, место `1`, `2` часа, `1000` cents. Покажите COMPLETED и три committed результата.
4. Menu 2: запомните автоматически созданный ID. Покажите A commit, B rejection и A compensation. Menu 4 подтверждает сохранённые статусы.
5. Menu 3: запомните автоматически созданный ID. Покажите A/B commit, C rejection, затем B compensation и A compensation. Menu 5 показывает порядок.
6. Menu 7: отмените `success-1`, чтобы освободить место 1. Повторите отмену и объясните idempotency.
7. Menu 9: новый ID `restart-1`, валидные данные и место 1. Покажите PENDING_B. Завершите процесс после напечатанного checkpoint.
8. Запустите приложение заново с тем же `--db`. Menu 4 показывает сохранённое A. Menu 6 доводит workflow до конца.
9. Menu 8: по очереди `gate`, `spaces`, `payment`. Для spaces место 3 должно быть свободным. Объясните отсутствие ParkingFacade workflow в этих демонстрациях.
10. Menu 1: высота `230`. Покажите REJECTED без domain effects. Завершите menu 0.

Не удаляйте БД между шагами проверки restart. Для новой демонстрации используйте новое имя файла или документированную reset-команду только после остановки приложения.

## Вопросы экзаменатора

1. **Где именно Facade?** `ParkingFacade.park` предоставляет одно действие, а `resume/advance/compensate` координируют несколько независимых API.
2. **Почему недостаточно одного controller?** Controller CLI отвечает за ввод/вывод; domain sequencing и recovery принадлежат facade и доступны без CLI.
3. **Чем это отличается от Adapter?** Нет преобразования несовместимого interface; есть упрощение и координация нескольких capabilities.
4. **Почему именно две facades?** Обычному клиенту нужен booking, администратору — inspection/recovery/cancellation. Это разные поверхности доступа.
5. **Где independent business logic?** Plate/clearance policy, sensor availability policy и tariff/overstay policy отделены от entity и gateway.
6. **Почему каждой подсистеме свой gateway?** Она сама сохраняет свой domain state и может работать без facade.
7. **Почему общий Database не нарушает независимость?** Это общий технический transaction helper; подсистемы не знают схемы или API друг друга.
8. **Что уже сохранено, когда B падает?** A HELD и progress PENDING_B. После compensation A CANCELLED, workflow COMPENSATED.
9. **Что уже сохранено, когда C падает?** A HELD, B RESERVED, progress PENDING_C; payment при недостаточном tender отсутствует.
10. **Как доказан порядок B → A?** Порядком кода, durable event sequence и assertion в специальном тесте, а также CLI history.
11. **Почему нельзя просто SQL rollback?** Предыдущие stages уже завершили commit; их effects нужно отменять новыми domain operations.
12. **Что если процесс упал между commit и journal?** Тот же шаг безопасно повторяется по operation ID, проверяя сохранённую запись.
13. **Что если повторный запрос имеет другую сумму?** Existing operation ID с другим request отклоняется, чтобы не подменить уже committed operation.
14. **Что если compensation B сама падает?** A не отменяется; COMPENSATING сохраняется; recovery повторяет после исправления причины.
15. **Почему entity не допускает повторный terminal transition?** Он нелегален для модели. Gateway распознаёт уже выполненный effect и делает повтор запроса безопасным.
16. **Зачем статус AUTHORIZED, если booking сразу CAPTURED?** Payment API поддерживает отдельную authorization/capture; common workflow выполняет оба как один локальный платёжный шаг.
17. **Что означает COMPLETED при HELD и RESERVED?** Предоплаченное бронирование подтверждено; физическое использование — отдельные domain transitions.
18. **Доказывает ли coverage отсутствие ошибок?** Нет. Оно показывает выполнение строк, а корректность поведения проверяют assertions и анализ требований.
19. **Что нельзя проверить локальными тестами?** Реальный банк/датчики не подключены; также нельзя подтвердить уникальность относительно работ других студентов или Moodle submission.
20. **Как изменить систему без нарушения boundaries?** Новое правило помещаем в его подсистему, новый порядок/compensation — в facade, ввод — в CLI, затем добавляем соответствующий тест.

## Возможные задания live coding

### 1. Запретить длинные автомобили на месте 3

Сначала определите новое входное поле `lengthCm` и правило. Измените `workflow/ParkingRequest`, сохранение request в `WorkflowStore`, CLI parsing, `ParkingReservation` и gateway schema, затем `SpaceSensorGrid` и `ReservationManager`. В `facade/ParkingFacade.advance` передайте новое поле в B. Измените `spaces/ReservationManagerTest`, `facade/ParkingFacadeTest` и `app/ParkingConsoleTest` в `src/test/java/com/smartparking/`. Gate не должен импортировать spaces. Проверка длины места принадлежит B. Добавьте standalone test правила и workflow test: A committed, B rejected, A compensated. Объясните, что изменение persisted schema требует migration либо отдельной чистой demo БД; нельзя молча стереть существующую.

### 2. Уменьшить clearance с 220 до 210 cm

Измените правило в `gate/LicensePlateReader`, инвариант `GateAdmission` и соответствующее ограничение SQL в `GateGateway`, если оно повторяет границу. В `src/test/java/com/smartparking/gate/GateControllerTest.java` добавьте boundary tests на 210/211 cm и в `src/test/java/com/smartparking/facade/ParkingFacadeTest.java` workflow test REJECTED без A/B/C effects. Изменение правила затрагивает уже сохранённые записи: заранее решите, разрешено ли читать старое разрешение, выданное при старом лимите. На защите не меняйте только сообщение об ошибке.

### 3. Отклонять предоплату более 12 часов

Измените `payment/OverstayAlert`, инварианты `ParkingPayment`, SQL constraint `PaymentGateway` и boundary tests в `src/test/java/com/smartparking/payment/PaymentTerminalTest.java`. В `src/test/java/com/smartparking/facade/ParkingFacadeTest.java` добавьте workflow test и укажите 13 часов при свободном месте: A и B должны commit, C должен reject, compensation B → A должна сохраниться. Такой тест проверяет новую причину C failure без изменения orchestration.

### 4. Добавить временный запрет release заблокированного места

Сначала согласуйте domain meaning: это отказ подтверждения освобождения датчиком, а не запрет бизнес-отмены без причины. Реализуйте проверку в src/main/java/com/smartparking/spaces/ReservationGateway.java и SpaceSensorGrid.java. В `src/test/java/com/smartparking/spaces/ReservationManagerTest.java` проверьте новое правило, а в `src/test/java/com/smartparking/facade/ParkingFacadeTest.java` добавьте тест частичной compensation: A/B commit, C fails, B release fails, состояние остаётся COMPENSATING и A остаётся HELD. После исправления sensor condition повторите recovery и проверьте RELEASED/CANCELLED. Обязательно добавьте проверку, что преждевременной A compensation нет.

### 5. Изменить тариф на первые два часа и последующие часы

Измените pure pricing policy `payment/OverstayAlert.priceCents` и инварианты суммы в `ParkingPayment`/`PaymentGateway`. В `src/test/java/com/smartparking/payment/PaymentTerminalTest.java` добавьте table-driven tests для 1, 2, 3 и 24 часов, insufficient tender на один cent и happy path. Подумайте о версии тарифа: старый persisted payment должен хранить фактически применённую сумму, а не становиться нечитаемым после изменения глобального правила. Для полноценной migration лучше убрать повторный пересчёт старой суммы из read-конструктора и хранить tariff version.

Для любого упражнения сначала сформулируйте наблюдаемое поведение и тест, затем меняйте минимальный слой-владелец правила. Запустите соответствующий test class, после этого `clean verify` и живой CLI сценарий. Не добавляйте прямой вызов другой подсистемы ради быстрого исправления.


