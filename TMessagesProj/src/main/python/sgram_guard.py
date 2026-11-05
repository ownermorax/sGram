"""Plugin Guard: static check of a plugin before install (signatures from Plugin Guard 2.0 by @dekma0091 + our own rules).

scan(source) -> {"level": "clean" | "warning" | "danger", "score": 0..100, "findings": [...], "sectors": [...],
                 "audit": {...}, "layers": [{"title", "line", "code"}]}
A finding: {severity, id, title, text, line, lines, layer, detail}. Lines of layer > 0 are lines inside that unpacked layer.
"""
import ast
import base64
import binascii
import codecs
import json
import math
import re
import zlib

CRITICAL = "CRITICAL"
WARNING = "WARNING"
VERSION = "PG sGram 2.0"
SECTORS = 20

_db = None


def _load_db():
    global _db
    if _db is None:
        try:
            from sgram_guard_db import DB
            _db = dict(DB)
        except Exception:
            _db = {"rules": {}, "strings": [], "names": [], "dex": [{}, {}, {}]}
        _db["names_map"] = {n: (sev, rid) for n, sev, rid in _db["names"]}
        for n, sev, rid in _NAMES:
            _db["names_map"].setdefault(n, (sev, rid))
    return _db


# Russian titles (ours and the most common ones of the signature table).
_TITLES = {
    "SENSITIVE_FILE_ACCESS": ("Поиск файлов авторизации", "Обращается к внутренним файлам Telegram: ключи сессии, база сообщений, настройки."),
    "SHELL_COMMAND": ("Системные команды", "Запускает команды оболочки Android."),
    "NETWORK_WITH_ACCOUNT_DATA": ("Данные аккаунта + сеть", "Читает данные аккаунта (телефон, сессия, пользователь) и отправляет запросы в интернет."),
    "RAW_IP_ENDPOINT": ("Подключение к IP-адресу", "Обращается к серверу по голому IP-адресу, а не домену — типично для серверов злоумышленников."),
    "REMOTE_CODE_EXECUTION_STAGER": ("Загрузка кода из интернета", "Скачивает и сразу выполняет непроверенный код из сети."),
    "XOR_ENCRYPTED_PAYLOAD_EXEC": ("Выполнение зашифрованного кода", "Расшифровывает (XOR) спрятанный код и выполняет его."),
    "XOR_DECRYPTOR": ("XOR-расшифровщик", "Функция побайтово расшифровывает данные ключом — так прячут вредоносный код."),
    "DYNAMIC_CODE_EXECUTION": ("Выполнение кода", "Выполняет строку как Python-код (exec/eval)."),
    "DYNAMIC_CODE_COMPILATION": ("Компиляция кода", "Собирает код на лету (compile)."),
    "MULTI_LAYER_OBFUSCATION_UNPACKED": ("Многослойная обфускация", "Внутри спрятан код, упакованный в несколько слоёв (base64/zlib/hex)."),
    "OBFUSCATED_CODE_STRUCTURE": ("Обфусцированная структура кода", "Случайные имена и длинные hex/base64-строки: код намеренно запутан."),
    "HIGH_ENTROPY_LITERAL_DETECTED": ("Зашифрованные данные", "Длинная строка с высокой энтропией — похоже на шифрованный или сжатый блок."),
    "ACTIVE_SESSIONS_HARVESTING": ("Сбор списка сессий", "Плагин сканирует список активных устройств вашего аккаунта."),
    "TELEGRAM_ACCOUNT_DATA_HARVESTING": ("Кража данных аккаунта", "Читает ключ авторизации или номер телефона."),
    "ACCOUNT_DESTRUCTIVE_ACTION": ("Разрушительные действия с аккаунтом", "Выход со всех устройств, удаление аккаунта или смена номера."),
    "SESSION_HIJACK": ("Перехват входа", "Создаёт токен входа/авторизации — так угоняют аккаунт на другое устройство."),
    "TWO_FA_CHANGE": ("Изменение облачного пароля", "Меняет или сбрасывает пароль 2FA и почту восстановления."),
    "CONTACTS_HARVESTING": ("Сбор контактов", "Выгружает список контактов."),
    "AUTO_JOIN_CHANNEL": ("Автоподписка", "Сам вступает в каналы/чаты по ссылке."),
    "MESSAGES_DB_READ": ("Чтение базы данных сообщений", "Прямой SQL-запрос к локальной базе переписок Telegram."),
    "LOCAL_GALLERY_EXFILTRATION": ("Попытка доступа к фото", "Плагин ищет личные фотографии и скриншоты."),
    "STORAGE_WALK": ("Обход файлов", "Перебирает файлы на накопителе."),
    "ARCHIVE_CREATION": ("Упаковка файлов в архив", "Собирает файлы в архив — часто перед отправкой наружу."),
    "C2_TELEGRAM_BOT_API": ("Канал связи через Telegram-бота", "Использует Bot API для отправки данных злоумышленнику."),
    "EMBEDDED_TELEGRAM_BOT_TOKEN": ("Вшитый токен бота", "В коде спрятан токен Telegram-бота — канал для утечки данных."),
    "C2_WEBHOOK": ("Отправка на вебхук", "Отправляет данные на Discord/вебхук-сервис."),
    "IP_LOGGER_TRACKER_EXFILTRATION": ("IP-логгер", "Обращается к сервисам, которые записывают ваш IP."),
    "REMOTE_PASTE_RESOURCE": ("Код с внешнего хостинга", "Тянет данные с pastebin/raw.githubusercontent — их могут подменить в любой момент."),
    "FORCE_MAX_VOLUME_AUDIO_EXPLOIT": ("Громкость на максимум", "Принудительно выкручивает системную громкость (setStreamVolume → getStreamMaxVolume)."),
    "VOLUME_CONTROL": ("Управление громкостью", "Меняет системную громкость."),
    "CLIPBOARD_READ": ("Чтение буфера обмена", "Читает содержимое буфера обмена."),
    "CLIPBOARD_CRYPTO_CLIPPER": ("Подмена крипто-кошелька", "Подменяет адреса кошельков в буфере обмена."),
    "HIDDEN_ATTRIBUTE_ACCESS": ("Скрытый вызов метода", "Имя метода собирается на лету (getattr) — так прячут опасные вызовы."),
    "HIDDEN_NAME_REVEALED": ("Раскрыто спрятанное имя", "Имя, собранное из символов, совпало с опасным API."),
    "DATA_EXFILTRATION": ("Утечка данных", "Читает чувствительные данные и имеет канал отправки наружу."),
    "CTYPES_NATIVE_ACCESS": ("Нативный доступ (ctypes)", "Вызывает нативные функции в обход Python."),
    "DYNAMIC_IN_MEMORY_DEX_LOADER": ("Загрузка DEX из памяти", "Запускает скомпилированный Java-код, который нельзя проверить."),
    "DYNAMIC_NATIVE_LIBRARY_LOAD": ("Загрузка нативной библиотеки", "Подгружает .so-библиотеку."),
    "PRIVILEGED_COMMAND_EXECUTION": ("Запуск процессов", "Запускает команды через Runtime.exec/ProcessBuilder."),
    "ROOT_COMMAND_EXECUTION": ("Root-команды", "Пытается получить права суперпользователя."),
    "PROCESS_TERMINATION_CALL": ("Завершение процесса", "Может убить приложение."),
    "FRAME_ENVIRONMENT_THEFT": ("Доступ к чужим переменным", "Лезет в стек вызовов и глобальные переменные других модулей."),
    "SELF_CODE_PAYLOAD_EXTRACTION": ("Чтение своего файла", "Читает собственный код, чтобы распаковать спрятанные функции."),
    "INDIRECT_DYNAMIC_EXECUTION": ("Скрытый импорт", "Импортирует модуль по вычисляемому имени."),
    "BYTE_XOR_DECRYPTION_LOOP": ("XOR-расшифровка символов", "Побайтовая расшифровка через ord/chr."),
    "MALICIOUS_LANG_PACK_INSTALL": ("Установка языкового пакета", "Подсовывает чужой языковой пакет (подмена текстов интерфейса)."),
    "GIFT_STAR_MANIPULATION": ("Операции с подарками и звёздами", "Передаёт подарки/звёзды или платные реакции."),
    "MASS_REPORT_ABUSE": ("Массовые жалобы", "Отправляет жалобы на пользователей/чаты."),
    "SYNTAX_ERROR": ("Ошибка синтаксиса", "Код не разбирается — проверены только строки."),
    "SUSPICIOUS_SIGNATURE_MATCH": ("Подозрительная сигнатура", "Похоже на известные вредоносные плагины."),
}

# Extra dangerous identifiers (name, severity, rule).
_NAMES = [
    ("TL_auth_exportLoginToken", CRITICAL, "SESSION_HIJACK"), ("TL_auth_acceptLoginToken", CRITICAL, "SESSION_HIJACK"),
    ("TL_auth_importLoginToken", CRITICAL, "SESSION_HIJACK"), ("TL_auth_exportAuthorization", CRITICAL, "SESSION_HIJACK"),
    ("TL_account_resetAuthorization", CRITICAL, "ACCOUNT_DESTRUCTIVE_ACTION"),
    ("TL_account_getAuthorizations", CRITICAL, "ACTIVE_SESSIONS_HARVESTING"),
    ("TL_account_updatePasswordSettings", CRITICAL, "TWO_FA_CHANGE"), ("TL_account_getPasswordSettings", CRITICAL, "TWO_FA_CHANGE"),
    ("TL_auth_recoverPassword", CRITICAL, "TWO_FA_CHANGE"), ("TL_account_resetPassword", CRITICAL, "TWO_FA_CHANGE"),
    ("TL_account_sendChangePhoneCode", CRITICAL, "ACCOUNT_DESTRUCTIVE_ACTION"),
    ("TL_contacts_getContacts", WARNING, "CONTACTS_HARVESTING"), ("TL_contacts_exportContactToken", WARNING, "CONTACTS_HARVESTING"),
    ("TL_messages_importChatInvite", WARNING, "AUTO_JOIN_CHANNEL"), ("TL_channels_joinChannel", WARNING, "AUTO_JOIN_CHANNEL"),
    ("TL_account_reportPeer", WARNING, "MASS_REPORT_ABUSE"), ("TL_messages_report", WARNING, "MASS_REPORT_ABUSE"),
    ("getPrimaryClip", WARNING, "CLIPBOARD_READ"),
    ("setStreamVolume", WARNING, "VOLUME_CONTROL"), ("adjustStreamVolume", WARNING, "VOLUME_CONTROL"),
    ("queryFinalized", WARNING, "MESSAGES_DB_READ"), ("executeFast", WARNING, "MESSAGES_DB_READ"),
    ("make_archive", WARNING, "ARCHIVE_CREATION"), ("ZipFile", WARNING, "ARCHIVE_CREATION"),
    ("walk", WARNING, "STORAGE_WALK"), ("listdir", WARNING, "STORAGE_WALK"), ("scandir", WARNING, "STORAGE_WALK"),
    ("listFiles", WARNING, "STORAGE_WALK"), ("getExternalStorageDirectory", WARNING, "STORAGE_WALK"),
]
_WEAK_NAMES = {"walk", "listdir", "scandir", "listFiles", "ZipFile", "make_archive"}  # only matter as calls/attributes

_STRINGS = [
    ("discord.com/api/webhooks", CRITICAL, "C2_WEBHOOK"), ("discordapp.com/api/webhooks", CRITICAL, "C2_WEBHOOK"),
    ("webhook.site", CRITICAL, "C2_WEBHOOK"), ("pipedream.net", CRITICAL, "C2_WEBHOOK"), ("requestbin", CRITICAL, "C2_WEBHOOK"),
    ("ngrok", WARNING, "C2_WEBHOOK"), ("trycloudflare.com", WARNING, "C2_WEBHOOK"),
    ("grabify", CRITICAL, "IP_LOGGER_TRACKER_EXFILTRATION"), ("iplogger", CRITICAL, "IP_LOGGER_TRACKER_EXFILTRATION"),
    ("2no.co", CRITICAL, "IP_LOGGER_TRACKER_EXFILTRATION"), ("ipinfo.io", WARNING, "IP_LOGGER_TRACKER_EXFILTRATION"),
    ("api.ipify.org", WARNING, "IP_LOGGER_TRACKER_EXFILTRATION"),
    ("pastebin.com", WARNING, "REMOTE_PASTE_RESOURCE"), ("raw.githubusercontent.com", WARNING, "REMOTE_PASTE_RESOURCE"),
    ("gist.githubusercontent.com", WARNING, "REMOTE_PASTE_RESOURCE"), ("hastebin", WARNING, "REMOTE_PASTE_RESOURCE"),
    ("/sendmessage", WARNING, "C2_TELEGRAM_BOT_API"), ("/senddocument", CRITICAL, "C2_TELEGRAM_BOT_API"),
    ("/dcim", CRITICAL, "LOCAL_GALLERY_EXFILTRATION"), ("screenshots", WARNING, "LOCAL_GALLERY_EXFILTRATION"),
    ("/pictures", WARNING, "LOCAL_GALLERY_EXFILTRATION"), ("mediastore", WARNING, "LOCAL_GALLERY_EXFILTRATION"),
    ("/storage/emulated", WARNING, "STORAGE_WALK"), ("/sdcard", WARNING, "STORAGE_WALK"),
    ("from messages", CRITICAL, "MESSAGES_DB_READ"), ("messages_v2", CRITICAL, "MESSAGES_DB_READ"),
    ("from dialogs", WARNING, "MESSAGES_DB_READ"), ("from users", WARNING, "MESSAGES_DB_READ"),
    ("getstreammaxvolume", WARNING, "VOLUME_CONTROL"),
]

_BOT_TOKEN = re.compile(r"(?<!\d)\d{8,10}:[A-Za-z0-9_-]{35}\b")
_IP_URL = re.compile(r"\b(?:https?|wss?|tcp)://(\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?[^\s'\"]*", re.I)
_URL = re.compile(r"\b(?:https?|wss?)://[^\s'\"<>]+", re.I)
_HEX = re.compile(r"^[0-9a-fA-F]{24,}$")
_RANDOM_NAME = re.compile(r"^_+[a-z]{4,10}$")
_SENSITIVE_FILES = ("tgnet.dat", "cache4.db", "userconfing", "userconfig.xml", "shared_prefs", "/data/data/org.telegram",
                    "/data/user/0/", "mainconfig.xml", "account_", "keys.dat")
_EXEC_FUNCS = {"exec", "eval"}
_SHELL = {"system", "popen", "spawnl", "spawnv", "execv", "execl", "execve", "execvp"}
_KILL = {"_exit", "kill", "killProcess", "abort"}
_ACCOUNT = {"getClientPhone", "getCurrentUser", "getAuthKey", "phone", "getPhone"}
_NET = {"requests", "urllib", "http", "socket", "HttpURLConnection", "aiohttp", "httpx", "urlopen"}
_NET_CALLS = {"get", "post", "urlopen", "request", "read", "recv", "content", "text", "json"}
_SENSITIVE_RULES = {"SENSITIVE_FILE_ACCESS", "MESSAGES_DB_READ", "LOCAL_GALLERY_EXFILTRATION", "ACTIVE_SESSIONS_HARVESTING",
                    "TELEGRAM_ACCOUNT_DATA_HARVESTING", "CONTACTS_HARVESTING", "CLIPBOARD_READ", "TWO_FA_CHANGE"}
_EXFIL_RULES = {"C2_TELEGRAM_BOT_API", "EMBEDDED_TELEGRAM_BOT_TOKEN", "C2_WEBHOOK", "RAW_IP_ENDPOINT"}


class _Scanner(ast.NodeVisitor):
    def __init__(self, report, depth):
        self.r = report
        self.depth = depth
        self.names = set()
        self.net = False
        self.account = False
        self.net_vars = set()
        self.xor_funcs = set()
        self.random_names = set()
        self.hex_literals = 0

    def _name(self, node):
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            return node.attr
        return None

    def _full(self, node):
        parts = []
        while isinstance(node, ast.Attribute):
            parts.append(node.attr)
            node = node.value
        if isinstance(node, ast.Name):
            parts.append(node.id)
        return ".".join(reversed(parts))

    def _ident(self, name, line, call=False):
        self.names.add(name)
        if _RANDOM_NAME.match(name):
            self.random_names.add(name)
        hit = _load_db()["names_map"].get(name)
        if hit and (call or name not in _WEAK_NAMES):
            self.r.add(hit[0], hit[1], line, name)
        if name in _ACCOUNT:
            self.account = True
        if name in _NET:
            self.net = True

    def visit_Name(self, node):
        self._ident(node.id, node.lineno)

    def visit_Attribute(self, node):
        self._ident(node.attr, node.lineno, True)
        self.generic_visit(node)

    def visit_FunctionDef(self, node):
        self._ident(node.name, node.lineno)
        for sub in ast.walk(node):
            if isinstance(sub, ast.BinOp) and isinstance(sub.op, ast.BitXor) and any(
                    isinstance(x, ast.Subscript) for x in (sub.left, sub.right)):
                self.xor_funcs.add(node.name)
                self.r.add(WARNING, "XOR_DECRYPTOR", node.lineno, node.name)
                break
        self.generic_visit(node)

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_Assign(self, node):
        if self._is_net_value(node.value):
            for t in node.targets:
                if isinstance(t, ast.Name):
                    self.net_vars.add(t.id)
        self.generic_visit(node)

    def _is_net_value(self, node):
        for sub in ast.walk(node):
            if isinstance(sub, ast.Call):
                full = self._full(sub.func)
                if full.split(".")[0] in ("requests", "urllib", "httpx", "aiohttp", "http") or self._name(sub.func) == "urlopen":
                    return True
            if isinstance(sub, ast.Name) and sub.id in self.net_vars:
                return True
        return False

    def visit_Import(self, node):
        for a in node.names:
            self._module(a.name, node.lineno)

    def visit_ImportFrom(self, node):
        self._module(node.module or "", node.lineno)
        for a in node.names:
            self._ident(a.name, node.lineno)

    def _module(self, mod, line):
        top = mod.split(".")[0]
        self.r.imports.add(mod)
        if top == "ctypes":
            self.r.add(CRITICAL, "CTYPES_NATIVE_ACCESS", line, mod)
        elif top in ("subprocess", "pty"):
            self.r.add(CRITICAL, "SHELL_COMMAND", line, mod)
        elif top == "marshal":
            self.r.add(WARNING, "OBFUSCATED_CODE_STRUCTURE", line, mod)
        if top in _NET:
            self.net = True

    def visit_Constant(self, node):
        if isinstance(node.value, str):
            if _HEX.match(node.value):
                self.hex_literals += 1
            self.r.string(node.value, node.lineno, self.depth)
            hit = _load_db()["names_map"].get(node.value)
            if hit and node.value not in _WEAK_NAMES:  # getattr(obj, "getAuthKey") and friends
                self.r.add(hit[0], hit[1], node.lineno, node.value)
        elif isinstance(node.value, bytes):
            self.r.blob(node.value, node.lineno, self.depth)

    def visit_BinOp(self, node):
        if isinstance(node.op, ast.BitXor) and any(isinstance(x, ast.Call) and self._name(x.func) in ("ord", "chr") for x in (node.left, node.right)):
            self.r.add(WARNING, "BYTE_XOR_DECRYPTION_LOOP", node.lineno)
        if isinstance(node.op, ast.Add):  # "get" + "AuthKey"
            v = _const_eval(node)
            if isinstance(v, str) and not isinstance(node.left, ast.Constant) or isinstance(v, str) and not isinstance(node.right, ast.Constant):
                self._revealed(v, node.lineno)
            elif isinstance(v, str):
                self._revealed(v, node.lineno)
        self.generic_visit(node)

    def _revealed(self, value, line):
        hit = _load_db()["names_map"].get(value)
        if hit and value not in _WEAK_NAMES:
            self.r.add(hit[0], hit[1], line, value)
            self.r.add(WARNING, "HIDDEN_NAME_REVEALED", line, value)
        if isinstance(value, str) and len(value) > 3:
            self.r.string(value, line, self.depth)

    def _remote_arg(self, node):
        for sub in ast.walk(node):
            if isinstance(sub, ast.Name) and sub.id in self.net_vars:
                return True
            if isinstance(sub, ast.Call):
                full = self._full(sub.func)
                if full.split(".")[0] in ("requests", "urllib", "httpx") or self._name(sub.func) == "urlopen":
                    return True
            if isinstance(sub, ast.Attribute) and sub.attr in ("text", "content") and isinstance(sub.value, (ast.Call, ast.Name)):
                if isinstance(sub.value, ast.Call) or getattr(sub.value, "id", "") in self.net_vars:
                    return True
        return False

    def _uses_xor(self, node):
        for sub in ast.walk(node):
            if isinstance(sub, ast.Call) and self._name(sub.func) in self.xor_funcs:
                return True
            if isinstance(sub, ast.BinOp) and isinstance(sub.op, ast.BitXor):
                return True
        return False

    def visit_Call(self, node):
        name = self._name(node.func)
        full = self._full(node.func)
        line = node.lineno
        args = node.args
        if name in _EXEC_FUNCS and isinstance(node.func, ast.Name):
            decoded = _const_eval(args[0]) if args else None
            if args and self._remote_arg(args[0]):
                self.r.add(CRITICAL, "REMOTE_CODE_EXECUTION_STAGER", line)
            if isinstance(decoded, (str, bytes)) and not isinstance(args[0], ast.Constant):
                self.r.add(CRITICAL, "XOR_ENCRYPTED_PAYLOAD_EXEC" if self._uses_xor(args[0]) else "DYNAMIC_CODE_EXECUTION", line)
                self.r.payload(decoded, line, self.depth, True)
            elif args and isinstance(args[0], ast.Constant):
                self.r.add(WARNING, "DYNAMIC_CODE_EXECUTION", line)
                self.r.payload(args[0].value, line, self.depth, True)
            elif args and self._uses_xor(args[0]):
                self.r.add(CRITICAL, "XOR_ENCRYPTED_PAYLOAD_EXEC", line)
            else:
                self.r.add(CRITICAL, "DYNAMIC_CODE_EXECUTION", line)
        elif name == "compile" and isinstance(node.func, ast.Name):
            self.r.add(CRITICAL, "DYNAMIC_CODE_COMPILATION", line)
            if args and self._remote_arg(args[0]):
                self.r.add(CRITICAL, "REMOTE_CODE_EXECUTION_STAGER", line)
        elif name == "__import__" or full.endswith("importlib.import_module"):
            v = _const_eval(args[0]) if args else None
            if isinstance(v, str):
                self._module(v, line)
                if not isinstance(args[0], ast.Constant):
                    self.r.add(WARNING, "INDIRECT_DYNAMIC_EXECUTION", line, v)
            else:
                self.r.add(WARNING, "INDIRECT_DYNAMIC_EXECUTION", line)
        elif name == "getattr" and isinstance(node.func, ast.Name) and len(args) >= 2 and not isinstance(args[1], ast.Constant):
            v = _const_eval(args[1])
            if isinstance(v, str):
                self._revealed(v, line)
            self.r.add(WARNING, "HIDDEN_ATTRIBUTE_ACCESS", line, v if isinstance(v, str) else None)
        elif full in ("marshal.loads", "pickle.loads", "types.FunctionType", "types.CodeType"):
            self.r.add(CRITICAL, "MULTI_LAYER_OBFUSCATION_UNPACKED", line, full)
        elif name in _SHELL and full.startswith("os."):
            self.r.add(CRITICAL, "SHELL_COMMAND", line, full)
        elif name == "exec" and "Runtime" in full or name == "ProcessBuilder":
            self.r.add(CRITICAL, "PRIVILEGED_COMMAND_EXECUTION", line, full)
        elif name in _KILL and (full.startswith("os.") or "Process" in full):
            self.r.add(WARNING, "PROCESS_TERMINATION_CALL", line, full)
        elif name in ("load", "loadLibrary") and "System" in full:
            self.r.add(CRITICAL, "DYNAMIC_NATIVE_LIBRARY_LOAD", line, full)
        elif name in ("DexClassLoader", "InMemoryDexClassLoader", "PathClassLoader", "BaseDexClassLoader"):
            self.r.add(CRITICAL, "DYNAMIC_IN_MEMORY_DEX_LOADER", line, name)
        elif name == "open" and args and self._name(args[0]) == "__file__":
            self.r.add(WARNING, "SELF_CODE_PAYLOAD_EXTRACTION", line)
        elif name == "setPrimaryClip" and self.r.wallet_regex:
            self.r.add(CRITICAL, "CLIPBOARD_CRYPTO_CLIPPER", line)
        elif name in ("_getframe", "currentframe") or full.endswith("f_globals"):
            self.r.add(WARNING, "FRAME_ENVIRONMENT_THEFT", line, full)
        elif name == "setStreamVolume" and any(isinstance(s, ast.Call) and self._name(s.func) == "getStreamMaxVolume" for a in args for s in ast.walk(a)):
            self.r.add(CRITICAL, "FORCE_MAX_VOLUME_AUDIO_EXPLOIT", line, "setStreamVolume -> getStreamMaxVolume")
        if name in ("walk", "listdir", "scandir", "glob", "iglob", "make_archive", "ZipFile", "listFiles"):
            self._ident(name, line, True)
        if isinstance(node.func, ast.Name) and name in ("exec", "eval", "compile"):
            self.names.add(name)
            for a in node.args[1:]:
                self.visit(a)
            for k in node.keywords:
                self.visit(k)
            if args and not isinstance(args[0], ast.Constant):
                self.visit(args[0])
            return
        self.generic_visit(node)


def _const_eval(node, depth=0):
    """Evaluates literal chains: b64/b85/hex/zlib/codecs decode, .decode(), [::-1], "a" + "b", "".join([...]), chr(N)."""
    if depth > 12:
        return None
    if isinstance(node, ast.Constant) and isinstance(node.value, (str, bytes)):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        a, b = _const_eval(node.left, depth + 1), _const_eval(node.right, depth + 1)
        if isinstance(a, str) and isinstance(b, str) or isinstance(a, bytes) and isinstance(b, bytes):
            return a + b
        return None
    if isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Slice):
        v = _const_eval(node.value, depth + 1)
        s = node.slice
        if v is not None and s.lower is None and s.upper is None and isinstance(s.step, ast.UnaryOp):
            return v[::-1]
        return None
    if not isinstance(node, ast.Call):
        return None
    if isinstance(node.func, ast.Name):
        if node.func.id == "chr" and node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, int):
            try:
                return chr(node.args[0].value)
            except Exception:
                return None
        if node.func.id == "bytes" and node.args and isinstance(node.args[0], (ast.List, ast.Tuple)):
            vals = [e.value for e in node.args[0].elts if isinstance(e, ast.Constant) and isinstance(e.value, int)]
            if len(vals) == len(node.args[0].elts):
                try:
                    return bytes(vals)
                except Exception:
                    return None
        return None
    if not isinstance(node.func, ast.Attribute):
        return None
    fn = node.func.attr
    if fn == "join" and isinstance(node.func.value, ast.Constant) and isinstance(node.func.value.value, str) and node.args \
            and isinstance(node.args[0], (ast.List, ast.Tuple)):
        parts = [_const_eval(e, depth + 1) for e in node.args[0].elts]
        if all(isinstance(p, str) for p in parts):
            return node.func.value.value.join(parts)
        return None
    if fn in ("decode", "encode") and not isinstance(node.func.value, ast.Name):
        v = _const_eval(node.func.value, depth + 1)
        if v is None:
            return None
        if fn == "decode":
            if isinstance(v, bytes):
                enc = node.args[0].value if node.args and isinstance(node.args[0], ast.Constant) else "utf-8"
                try:
                    return v.decode(enc, "replace")
                except Exception:
                    return v.decode("utf-8", "replace")
            return v
        return v.encode() if isinstance(v, str) else v
    if not node.args:
        return None
    v = _const_eval(node.args[0], depth + 1)
    if v is None:
        return None
    try:
        b = v.encode() if isinstance(v, str) else v
        if fn in ("b64decode", "decodebytes", "standard_b64decode", "urlsafe_b64decode"):
            return base64.urlsafe_b64decode(b) if fn.startswith("urlsafe") else base64.b64decode(b)
        if fn in ("b32decode", "b16decode", "b85decode", "a85decode"):
            return getattr(base64, fn)(b)
        if fn == "decompress":
            for w in (zlib.MAX_WBITS, -zlib.MAX_WBITS, 16 + zlib.MAX_WBITS):
                try:
                    return zlib.decompress(b, w)
                except Exception:
                    pass
            import lzma, bz2
            for m in (lzma, bz2):
                try:
                    return m.decompress(b)
                except Exception:
                    pass
            return None
        if fn in ("fromhex", "unhexlify", "a2b_hex"):
            return bytes.fromhex(v if isinstance(v, str) else v.decode())
        if fn == "decode" and len(node.args) > 1 and isinstance(node.args[1], ast.Constant):
            return codecs.decode(v, node.args[1].value)
    except Exception:
        return None
    return None


def _entropy(s):
    if not s:
        return 0.0
    counts = {}
    for ch in s:
        counts[ch] = counts.get(ch, 0) + 1
    n = float(len(s))
    return -sum(c / n * math.log2(c / n) for c in counts.values())


class _Report:
    def __init__(self):
        self.items = {}
        self.wallet_regex = False
        self.layer = 0          # index in self.layers of the code being scanned (0 = the plugin itself)
        self.anchor = None      # line of the plugin file where the current layer was found
        self.layers = []
        self.max_depth = 0
        self.max_entropy = 0.0
        self.urls = []
        self.imports = set()
        self.obf_lines = set()  # plugin lines with packed/encrypted data

    def add(self, severity, rid, line=0, detail=None):
        it = self.items.get(rid)
        if it is None:
            db = _load_db()["rules"]
            title, text = _TITLES.get(rid) or db.get(rid) or (rid.replace("_", " ").title(), "")
            it = self.items[rid] = {"severity": severity, "id": rid, "title": title, "text": text, "line": 0,
                                    "lines": [], "layer": self.layer, "anchors": [], "detail": ""}
        if severity == CRITICAL:
            it["severity"] = CRITICAL
        if detail and not it["detail"]:
            it["detail"] = str(detail)[:120]
        if line:
            if self.layer == it["layer"] and line not in it["lines"] and len(it["lines"]) < 40:
                it["lines"].append(line)
                if not it["line"]:
                    it["line"] = line
            a = line if self.layer == 0 else self.anchor
            if a and a not in it["anchors"]:
                it["anchors"].append(a)

    def string(self, s, line, depth):
        low = s.lower()
        for pat, sev, rid in list(_load_db()["strings"]) + _STRINGS:
            if pat.lower() in low:
                self.add(sev, rid, line, pat)
        if _BOT_TOKEN.search(s):
            self.add(CRITICAL, "EMBEDDED_TELEGRAM_BOT_TOKEN", line, _BOT_TOKEN.search(s).group(0)[:14] + "…")
        for m in _URL.finditer(s):
            if len(self.urls) < 30 and m.group(0) not in self.urls:
                self.urls.append(m.group(0)[:120])
        m = _IP_URL.search(s)
        if m and not m.group(1).startswith(("127.", "10.", "192.168.", "0.")):
            self.add(CRITICAL, "RAW_IP_ENDPOINT", line, m.group(0)[:80])
        for f in _SENSITIVE_FILES:
            if f in low and (f != "account_" or ".dat" in low or ".xml" in low):
                self.add(CRITICAL, "SENSITIVE_FILE_ACCESS", line, f)
        if "(uq|eq)" in low or "[ue]q" in low or "0x[a-f" in low or "bc1" in low and "[" in s:
            self.wallet_regex = True
        if low.strip() in ("su", "/system/bin/su", "/system/xbin/su") or low.startswith("su -c"):
            self.add(CRITICAL, "ROOT_COMMAND_EXECUTION", line, s)
        if len(s) >= 200 and " " not in s[:200]:
            e = _entropy(s)
            self.max_entropy = max(self.max_entropy, e)
            if self.layer == 0:
                self.obf_lines.add(line)
            if e > 5.0:
                self.add(WARNING, "HIGH_ENTROPY_LITERAL_DETECTED", line, "%.2f" % e)
            self.blob(s, line, depth)

    def blob(self, data, line, depth):
        """Long literal: try to unpack (base64/zlib/hex) and look inside, including embedded DEX."""
        b = data.encode("latin-1", "ignore") if isinstance(data, str) else data
        candidates = [b]
        for dec in (base64.b64decode, base64.urlsafe_b64decode, binascii.unhexlify, base64.b85decode):
            try:
                candidates.append(dec(b))
            except Exception:
                pass
        for c in list(candidates):
            for w in (zlib.MAX_WBITS, -zlib.MAX_WBITS, 16 + zlib.MAX_WBITS):
                try:
                    candidates.append(zlib.decompress(c, w))
                except Exception:
                    pass
        for c in candidates[1:]:
            if c[:4] == b"dex\n":
                self.dex(c, line)
            elif c[:2] == b"PK" or c[:4] == b"\x7fELF":
                self.add(CRITICAL, "DYNAMIC_IN_MEMORY_DEX_LOADER", line, "embedded binary")
            else:
                self.payload(c, line, depth)

    def dex(self, data, line):
        text = data.decode("latin-1")
        groups = _load_db()["dex"]
        for i, group in enumerate(groups):
            sev = WARNING if i == 1 else CRITICAL
            for rid, pats in group.items():
                for p in pats:
                    if p in text:
                        self.add(sev, rid, line, p)
                        break
        self.add(WARNING, "DYNAMIC_IN_MEMORY_DEX_LOADER", line, "embedded DEX")

    def payload(self, data, line, depth, executed=False):
        """Hidden code: decode and scan it as a plugin of its own."""
        if depth >= 5:
            self.add(WARNING, "MULTI_LAYER_OBFUSCATION_UNPACKED", line)
            return
        try:
            text = data.decode("utf-8") if isinstance(data, bytes) else data
        except Exception:
            return
        if not executed and len(text) < 40:
            return
        try:
            tree = ast.parse(text)
        except Exception:
            return
        if not any(isinstance(n, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef, ast.Call)) for n in ast.walk(tree)):
            return
        if depth > 0 or not executed:
            self.add(CRITICAL if executed or depth > 0 else WARNING, "MULTI_LAYER_OBFUSCATION_UNPACKED", line)
        if self.layer == 0:
            self.obf_lines.add(line)
        saved = (self.layer, self.anchor)
        if self.layer == 0:
            self.anchor = line
        self.layers.append({"title": "Слой %d" % (depth + 1), "line": line, "parent": self.layer, "code": text[:60000]})
        self.layer = len(self.layers)
        self.max_depth = max(self.max_depth, depth + 1)
        _scan_tree(tree, self, depth + 1)
        self.layer, self.anchor = saved


def _scan_tree(tree, report, depth):
    s = _Scanner(report, depth)
    s.visit(tree)
    if s.net and s.account:
        report.add(WARNING, "NETWORK_WITH_ACCOUNT_DATA", 0)
    if len(s.random_names) >= 12 and len(s.random_names) * 3 >= len(s.names) or s.hex_literals >= 6:
        report.add(WARNING, "OBFUSCATED_CODE_STRUCTURE", 0, "%d случайных имён, %d hex-строк" % (len(s.random_names), s.hex_literals))
    return s


def _penalty(it):
    if it["severity"] == CRITICAL:
        return 30 + 5 * min(len(it["anchors"]) - 1, 4) if it["anchors"] else 30
    return 5 + min(len(it["anchors"]), 3)


def scan(source):
    report = _Report()
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        report.add(WARNING, "SYNTAX_ERROR", e.lineno or 0, str(e.msg))
        for pat, sev, rid in list(_load_db()["strings"]) + _STRINGS:
            if pat.lower() in source.lower():
                report.add(sev, rid, 0, pat)
        tree = None
    funcs = 0
    if tree is not None:
        _scan_tree(tree, report, 0)
        funcs = sum(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) for n in ast.walk(tree))
    rules = set(report.items)
    if rules & _SENSITIVE_RULES and rules & _EXFIL_RULES:
        report.add(CRITICAL, "DATA_EXFILTRATION", 0, ", ".join(sorted((rules & _SENSITIVE_RULES) | (rules & _EXFIL_RULES))))
    if "FORCE_MAX_VOLUME_AUDIO_EXPLOIT" in report.items:
        report.items.pop("VOLUME_CONTROL", None)

    items = sorted(report.items.values(), key=lambda x: (x["severity"] != CRITICAL, x["anchors"][0] if x["anchors"] else 0))
    crit = [i for i in items if i["severity"] == CRITICAL]
    warn = [i for i in items if i["severity"] != CRITICAL]
    score = max(0, 100 - sum(_penalty(i) for i in items))
    if crit:
        score = min(score, 25)
    level = "danger" if crit else "warning" if warn else "clean"

    lines = source.split("\n")
    n = max(len(lines), 1)
    sectors = []
    per = max(1, -(-n // SECTORS))
    for k in range(SECTORS):
        a, b = k * per + 1, min((k + 1) * per, n)
        if a > n:
            sectors.append({"from": 0, "to": 0, "state": "empty", "titles": []})
            continue
        hits = [i for i in items if any(a <= x <= b for x in i["anchors"])]
        obf = any(a <= x <= b for x in report.obf_lines)
        state = "critical" if any(i["severity"] == CRITICAL for i in hits) else "obfuscated" if obf else "warning" if hits else "clean"
        sectors.append({"from": a, "to": b, "state": state, "titles": [i["title"] for i in hits][:8]})

    body = re.sub(r"\s+", "", source)
    if level == "danger":
        verdict = "Вредоносный обфусцированный код (%d сл.)" % report.max_depth if report.max_depth else "Опасный код"
    elif level == "warning":
        verdict = "Подозрительный код — проверьте перед установкой"
    else:
        verdict = "Угроз не найдено"
    audit = {
        "verdict": verdict,
        "obfuscation": report.max_depth,
        "entropy": round(_entropy(body), 4),
        "max_literal_entropy": round(report.max_entropy, 4),
        "lines": n,
        "functions": funcs,
        "imports": sorted(report.imports)[:40],
        "urls": report.urls,
        "rules": len(_load_db()["rules"]) + len(_TITLES),
        "signatures": len(_load_db()["names_map"]) + len(_load_db()["strings"]) + len(_STRINGS),
        "critical": len(crit),
        "warnings": len(warn),
    }
    return {"level": level, "score": score, "version": VERSION, "findings": items, "sectors": sectors,
            "audit": audit, "layers": report.layers}


def scan_file(path):
    with open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        return json.dumps(scan(f.read()))
