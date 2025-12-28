# streamlit_app.py
import json
import html
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol, Set

import streamlit as st


# ---------------- Provider ----------------

class DataProvider(Protocol):
    def get_image(self, image_id: int) -> bytes:
        """Return raw image bytes (png/jpg/webp) suitable for st.image(bytes)."""
        ...

    def go_to(self, message_id: int) -> None:
        """Navigate to message_id (query params / session_state / whatever you decide)."""
        ...

    def add_translation(self, message_id: int, lang: str, translation: str) -> Optional[str]:
        """
        Try to save translation.
        Return:
          - None on success
          - str error message on failure
        """
        ...


# ---------------- JSON model ----------------
# translate is placed at the END (optional / "just in case"), as requested

@dataclass
class Link:
    id: int                     # message_id in sqlite
    title: str                  # native title
    translate: Dict[str, str]   # lang -> title   (optional)

@dataclass
class Message:
    id: int
    image_id: Optional[int]
    text: Optional[str]         # native text
    links: List[Link]
    translate: Dict[str, str]   # lang -> text    (optional)


# ---------------- Parsing ----------------

def _parse_lang_map(obj: Any, ctx: str) -> Dict[str, str]:
    if obj is None:
        return {}
    if not isinstance(obj, dict):
        raise ValueError(f"{ctx} must be an object/dict {{lang: text}}")
    out: Dict[str, str] = {}
    for k, v in obj.items():
        if not isinstance(k, str) or not k:
            raise ValueError(f"{ctx} keys must be non-empty strings (lang codes)")
        if not isinstance(v, str) or v == "":
            raise ValueError(f"{ctx}[{k}] must be a non-empty string")
        out[k] = v
    return out

def _parse_links(obj: Any, ctx: str) -> List[Link]:
    if obj is None:
        return []
    if not isinstance(obj, list):
        raise ValueError(f"{ctx} must be a list")
    out: List[Link] = []
    for i, it in enumerate(obj):
        if not isinstance(it, dict):
            raise ValueError(f"{ctx}[{i}] must be an object")
        lid = it.get("id")
        title = it.get("title")
        if not isinstance(lid, int):
            raise ValueError(f"{ctx}[{i}].id must be an integer")
        if not isinstance(title, str) or title == "":
            raise ValueError(f"{ctx}[{i}].title must be a non-empty string")
        tr = _parse_lang_map(it.get("translate"), f"{ctx}[{i}].translate")
        out.append(Link(id=lid, title=title, translate=tr))
    return out

def parse_messages(payload: Any) -> List[Message]:
    if not isinstance(payload, list):
        raise ValueError("Root must be a JSON list: Messages[...]")

    out: List[Message] = []
    for i, it in enumerate(payload):
        if not isinstance(it, dict):
            raise ValueError(f"messages[{i}] must be an object")

        mid = it.get("id")
        if not isinstance(mid, int):
            raise ValueError(f"messages[{i}].id must be an integer")

        image_id = it.get("image_id")
        if image_id is not None and not isinstance(image_id, int):
            raise ValueError(f"messages[{i}].image_id must be an integer if provided")

        text = it.get("text")
        if text is not None and (not isinstance(text, str) or text == ""):
            raise ValueError(f"messages[{i}].text must be a non-empty string if provided")

        links = _parse_links(it.get("links"), f"messages[{i}].links")

        tr = _parse_lang_map(it.get("translate"), f"messages[{i}].translate")

        out.append(Message(
            id=mid,
            image_id=image_id,
            text=text,
            links=links,
            translate=tr
        ))
    return out


# ---------------- App state helpers ----------------

def collect_available_langs(messages: List[Message]) -> List[str]:
    langs: Set[str] = set()
    for m in messages:
        langs.update(m.translate.keys())
        for lk in m.links:
            langs.update(lk.translate.keys())
    return sorted(langs)

def get_selected_lang() -> Optional[str]:
    chosen = st.session_state.get("selected_lang", "<None>")
    return None if chosen == "<None>" else chosen

def set_selected_lang(lang: Optional[str]) -> None:
    st.session_state["selected_lang"] = "<None>" if lang is None else lang

def show_overlay_error_if_any():
    err = st.session_state.get("overlay_error")
    if err:
        # “поверх экрана” в Streamlit без модалки — это обычно верхний sticky-блок.
        # Делаем максимально заметно:
        st.error(err)
        if st.button("Ok", key="overlay_ok"):
            st.session_state["overlay_error"] = None
            st.rerun()


# ---------------- Rendering ----------------

def _render_link_translations_inline(lk: Link, selected_lang: Optional[str]) -> None:
    # Title already rendered as button; here render all translations.
    if not lk.translate:
        return

    parts: List[str] = []
    for lang in sorted(lk.translate.keys()):
        t = lk.translate[lang]
        label = f"{lang}: {t}"
        esc = html.escape(label)
        if selected_lang is not None and lang == selected_lang:
            parts.append(f"<b>{esc}</b>")
        else:
            parts.append(esc)

    st.markdown(
        "<div style='margin: -6px 0 10px 12px; opacity: 0.9; font-size: 0.95em;'>"
        + " · ".join(parts) +
        "</div>",
        unsafe_allow_html=True
    )

def render_links(links: List[Link], selected_lang: Optional[str], provider: DataProvider, msg_id: int) -> None:
    # 2.2.1: list links: title button first, then all translations (selected one bold)
    for lk in links:
        if st.button(lk.title, key=f"link_btn_{msg_id}_{lk.id}"):
            provider.go_to(lk.id)
        _render_link_translations_inline(lk, selected_lang)

def render_text_block(message: Message, selected_lang: Optional[str], provider: DataProvider) -> None:
    """
    2.2.3:
      - if selected_lang is None: show message.text
      - else: 2-col block:
          left: native text
          right: translation OR editor+save -> provider.add_translation
    """
    if not message.text:
        return

    if selected_lang is None:
        st.text(message.text)
        return

    # 2-col “table without header”
    left, right = st.columns(2, vertical_alignment="top")
    with left:
        st.text(message.text)

    with right:
        existing = message.translate.get(selected_lang)
        if existing:
            st.text(existing)
            return

        # No translation => editor + Save
        draft_key = f"draft_tr_{message.id}_{selected_lang}"
        if draft_key not in st.session_state:
            st.session_state[draft_key] = ""

        # No label: “таблица без заголовка”
        new_val = st.text_area(
            "",
            value=st.session_state[draft_key],
            key=f"ta_{message.id}_{selected_lang}",
            placeholder=f"Enter translation ({selected_lang})…",
            height=110
        )
        st.session_state[draft_key] = new_val

        if st.button("Save", key=f"save_{message.id}_{selected_lang}"):
            candidate = (st.session_state[draft_key] or "").strip()
            if not candidate:
                st.session_state["overlay_error"] = "Translation is empty."
                st.rerun()

            err = provider.add_translation(message.id, selected_lang, candidate)
            if err is None:
                # Update UI “as if saved” (and usually you’d persist in SQLite inside provider)
                message.translate[selected_lang] = candidate
                st.rerun()
            else:
                st.session_state["overlay_error"] = err
                st.rerun()

def render_message(message: Message, selected_lang: Optional[str], provider: DataProvider) -> None:
    """
    2.2: for each message output in order, skipping missing with no empty lines:
      2.2.1 links
      2.2.2 image
      2.2.3 text block logic
    """
    if message.links:
        render_links(message.links, selected_lang, provider, message.id)

    if message.image_id is not None:
        img = provider.get_image(message.image_id)
        if img:  # чтобы не рисовать пустоту
            st.image(img, width="content")

    render_text_block(message, selected_lang, provider)


# ---------------- Data loading ----------------

def load_messages_json() -> Any:
    """
    Real-ish skeleton:
      - if messages.json exists рядом с app -> грузим его
      - иначе demo payload
    """
    p = Path("messages.json")
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))

    # demo fallback
    return [
        {
            "id": 1,
            "image_id": 7,
            "text": "Hello World",
            "links": [],
            "translate": {"en": "Hi. This is text."}
        },
        {
            "id": 2,
            "image_id": None,
            "text": "Переходы:",
            "links": [
                {"id": 1001, "title": "Ветвь Альфа", "translate": {"en": "Branch Alpha", "es": "Rama Alfa"}},
                {"id": 1002, "title": "Ветвь Бета", "translate": {"en": "Branch Beta"}}
            ],
            "translate": {"en": "Links:"}
        },
        {
            "id": 3,
            "image_id": None,
            "text": "Тут перевода нет, попробуй добавить:",
            "links": [],
            "translate": {}
        }
    ]


# ---------------- Demo Provider (works without SQLite) ----------------

class DemoProvider:
    def __init__(self, messages: List[Message]):
        self._messages = {m.id: m for m in messages}

    def get_image(self, image_id: int) -> bytes:
        from PIL import Image, ImageDraw
        import io

        # нормальный “баннер” 100x300
        img = Image.new("RGB", (100, 100), (245, 120, 180))
        d = ImageDraw.Draw(img)
        d.text((20, 20), f"image_id = {image_id}", fill=(0, 0, 0))

        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()

    def go_to(self, message_id: int) -> None:
        # Skeleton navigation: store target id
        st.session_state["goto_message_id"] = message_id
        st.query_params["goto"] = str(message_id)
        st.rerun()

    def add_translation(self, message_id: int, lang: str, translation: str) -> Optional[str]:
        # Demo “save”: validate a bit and store in-memory
        if len(lang) > 32:
            return "Language code is too long."
        if len(translation) > 10_000:
            return "Translation is too long."
        msg = self._messages.get(message_id)
        if not msg:
            return f"Message {message_id} not found."
        msg.translate[lang] = translation
        return None


# ---------------- App ----------------

st.set_page_config(layout="wide")

# No title, as requested.
show_overlay_error_if_any()

# Load + parse always (no Render button)
try:
    payload = load_messages_json()
    messages = parse_messages(payload)
except Exception as e:
    st.session_state["overlay_error"] = f"Bad messages.json or schema: {e}"
    show_overlay_error_if_any()
    st.stop()

provider = DemoProvider(messages)

# Session state defaults
st.session_state.setdefault("extra_langs", set())      # languages added manually (in-memory)
st.session_state.setdefault("adding_lang", False)
st.session_state.setdefault("selected_lang", "<None>")

# ---------------- "Two-row table" ----------------
# Row 1: translation selector + add language (inline)
with st.container():
    base_langs = collect_available_langs(messages)
    all_langs = ["<None>"] + sorted(set(base_langs) | set(st.session_state["extra_langs"]))

    current = st.session_state.get("selected_lang", "<None>")
    if current not in all_langs:
        current = "<None>"
        st.session_state["selected_lang"] = current

    # (We use columns only to keep controls on one line. No global two-column layout.)
    c1, c2, c3 = st.columns([3, 2, 5], vertical_alignment="center")

    with c1:
        selected = st.selectbox(
            "Translation",
            options=all_langs,
            index=all_langs.index(current),
            key="translation_select"
        )
        st.session_state["selected_lang"] = selected

    with c2:
        if st.button("Add language", key="add_lang_btn"):
            st.session_state["adding_lang"] = True

    with c3:
        if st.session_state.get("adding_lang"):
            new_lang = st.text_input("Lang", key="new_lang_input", placeholder="e.g. es")
            s1, s2 = st.columns([1, 1], vertical_alignment="center")
            with s1:
                if st.button("Save language", key="save_lang_btn"):
                    nl = (new_lang or "").strip()
                    if not nl:
                        st.session_state["overlay_error"] = "Language name is empty."
                        st.rerun()
                    st.session_state["extra_langs"].add(nl)
                    st.session_state["selected_lang"] = nl
                    st.session_state["adding_lang"] = False
                    st.rerun()
            with s2:
                if st.button("Cancel", key="cancel_lang_btn"):
                    st.session_state["adding_lang"] = False
                    st.rerun()

st.markdown("<hr style='opacity:0.25'/>", unsafe_allow_html=True)

# Row 2: messages, in order
selected_lang = get_selected_lang()
for m in messages:
    render_message(m, selected_lang, provider)
    st.markdown("<hr style='opacity:0.20'/>", unsafe_allow_html=True)

# Optional debug (you can remove)
if "goto_message_id" in st.session_state:
    st.caption(f"goto_message_id = {st.session_state['goto_message_id']}")
