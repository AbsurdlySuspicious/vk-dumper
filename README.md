# vk-dumper

Утилита для сохранения всех сообщений и прямых ссылок на медиа из vk.com в машино-читаемом формате

## => Attention <=

VK закрывает доступ к messages api. Скорее всего, эта утилита сможет работать __лишь до 15 февраля__.
Подробности: https://vk.com/dev/messages_api

## Зачем?

VK дает возможность скачать "архив с данными", который представляет из себя набор html страниц, и не предоставляет никакой возможности загрузить машино-читаемые данные. Самое главное: архив не содержит прямых ссылок на медиа-файлы. Это означает, что их невозможно загрузить при отсутствии возможности зайти в аккаунт, а автоматическая загрузка будет затруднена

## Что дальше?

Перед выкладыванием кода еще оставалась задача - возможность выгрузить данные в elasticsearch, либо, возможно, какой-то более простой, но механизм поиска по сообщениям. В свете событий выше, приходится выкладывать ограниченую версию. Однако, функция поиска/выгрузки обязательно появится в будущем

## Инструкции и готовые сборки

Для запуска необходим __Java JRE 8__.

### Jar

Готовые jar файлы есть на [вкладке releases](https://github.com/AbsurdlySuspicious/vk-dumper/releases)

### AUR

Доступен пакет в AUR: https://aur.archlinux.org/packages/vk-dumper/

### Как пользоваться?

Запуск jar файлов как обычно:

```
java -jar vkdumper.jar ARGS
```

ИЛИ

Если используете AUR пакет:

```
vkdumper ARGS
```

`ARGS` - заменить на опции. Общее описание опций доступно по `--help`

### Как скачать сообщения?

ниже будут приведены только опции

1. `-G` - сгенерировать конфиг и поместить его в файл `vkdumper.cfg`
2. Добавить в конфиге в поле `token` свой access_token
3. `-D` - запустить сохранение сообщений

Процесс загрузки сообщений можно прервать в любой момент при помощи Ctrl+C. При следующем запуске загрузка продолжится с того же места, где была остановлена в прошлый раз.

#### Пример конфигурации

```
{
  "fallbackAttempts" : 3,
  "connectionTimeout" : 10,
  "readTimeout" : 10,
  "commonPar" : 4,
  "throttleCount" : 3,
  "throttlePeriod" : 2,
  "baseDir" : "DumpedData",
  "token" : "xxx"
}
```

Где вместо `xxx` должен быть ваш access_token. Вместо генерации конфига при помощи `-G` можно использовать этот

#### Как получить access_token?

воспользуйтесь ссылкой:

```
https://oauth.vk.com/authorize?client_id=6277091&scope=notify%2Cfriends%2Cphotos%2Caudio%2Cvideo%2Cdocs%2Cnotes%2Cmessages%2Cpages%2Cstatus%2Cwall%2Cgroups%2Cnotifications%2Cstats%2Cquestions%2Coffers%2Coffline&redirect_uri=blank.html&display=popup&response_type=token
```

(или [так](https://oauth.vk.com/authorize?client_id=6277091&scope=notify%2Cfriends%2Cphotos%2Caudio%2Cvideo%2Cdocs%2Cnotes%2Cmessages%2Cpages%2Cstatus%2Cwall%2Cgroups%2Cnotifications%2Cstats%2Cquestions%2Coffers%2Coffline&redirect_uri=blank.html&display=popup&response_type=token))

Затем скопируйте символы из адресной строки начиная от `access_token=` и до `&` (не включая сам символ `&`) и поместите их в файл конфигурации. Строка должна состоять только из символов a-f 0-9, не копируйте лишнего. В этой ссылке использован appid от Kate mobile, но можно использовать любой другой

## Формат

Сообщения сохраняются в формате csv+json (фактически, json массив без [] в начале и конце)

Схемы в формате case-классов:

- `msg` - https://github.com/AbsurdlySuspicious/vk-dumper/blob/master/src/main/scala/vkdumper/Api.scala#L94
- `clist` - https://github.com/AbsurdlySuspicious/vk-dumper/blob/master/src/main/scala/vkdumper/Api.scala#L127
- `users` - https://github.com/AbsurdlySuspicious/vk-dumper/blob/master/src/main/scala/vkdumper/Api.scala#L85

## Сборка

Для сборки необходим sbt / Scala build tool

В директории с проектом запустить:
```
sbt assembly
```
Результирующий jar файл будет находиться здесь:
```
target/scala-2.12/vkdumper.jar
```