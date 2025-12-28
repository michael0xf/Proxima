# app.py
import json
import html
from dataclasses import dataclass
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
# translate placed at the END, as requested

@dataclass
class Link:
    id: int                                  # message_id in sqlite
    title: str                               # native title
    translate: Dict[str, str]                # lang -> title   (optional)

@dataclass
class Message:
    id: int
    image_id: Optional[int]
    text: Optional[str]                      # native text
    links: List[Link]
    translate: Dict[str, str]                # lang -> text    (optional)


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
    """
    Root JSON is a LIST:
    [
      {
        "id": 1,
        "image_id": 42,
        "text": "native",
        "links": [{"id": 999, "title":"...", "translate":{"en":"..."}}],
        "translate": {"en":"..."}
      }
    ]
    """
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


# ---------------- UI helpers ----------------

def collect_available_langs(messages: List[Message]) -> List[str]:
    langs: Set[str] = set()
    for m in messages:
        langs.update(m.translate.keys())
        for lk in m.links:
            langs.update(lk.translate.keys())
    return sorted(langs)

def show_overlay_error_if_any():
    err = st.session_state.get("overlay_error")
    if err:
        with st.container():
            st.error(err)
            if st.button("Ok", key="overlay_ok"):
                st.session_state["overlay_error"] = None
                st.rerun()

def render_links_list(links: List[Link], selected_lang: Optional[str], provider: DataProvider, msg_id: int):
    """
    2.2.1: list links: title first, then all translations.
    If selected_lang exists in link.translate => bold that translation.
    Buttons in both "columns" lead to same action: provider.go_to(link.id).
    """
    for lk in links:
        # Title as a button (go_to by id)
        if st.button(lk.title, key=f"link_btn_{msg_id}_{lk.id}"):
            provider.go_to(lk.id)

        if lk.translate:
            parts = []
            for lang in sorted(lk.translate.keys()):
                t = lk.translate[lang]
                label = f"{lang}: {t}"
                if selected_lang is not None and lang == selected_lang:
                    parts.append(f"<b>{html.escape(label)}</b>")
                else:
                    parts.append(html.escape(label))
            st.markdown(
                "<div style='margin: -6px 0 10px 12px; opacity: 0.9; font-size: 0.95em;'>"
                + " · ".join(parts) +
                "</div>",
                unsafe_allow_html=True
            )

def render_message(message: Message, selected_lang: Optional[str], provider: DataProvider):
    """
    2.2: for each message output in order, skipping missing completely:
      2.2.1 links (list)
      2.2.2 image
      2.2.3 text / translation editor logic
    """
    # 2.2.1 links
    if message.links:
        render_links_list(message.links, selected_lang, provider, message.id)

    # 2.2.2 image
    if message.image_id is not None:
        st.image(provider.get_image(message.image_id), use_container_width=True)

    # 2.2.3 text logic
    native_text = message.text
    if selected_lang is None:
        # 2.2.3.1: show native text only
        if native_text:
            st.text(native_text)
        return

    # 2.2.3.2: selected lang -> show "table without header" 2 cols (original vs translation/edit)
    if not native_text:
        # No native text => nothing to show in this section
        return

    left, right = st.columns(2, vertical_alignment="top")
    with left:
        st.text(native_text)

    with right:
        existing = message.translate.get(selected_lang)
        if existing:
            st.text(existing)
        else:
            # editor + save
            draft_key = f"draft_tr_{message.id}_{selected_lang}"
            if draft_key not in st.session_state:
                st.session_state[draft_key] = ""

            st.session_state[draft_key] = st.text_area(
                "",
                value=st.session_state[draft_key],
                key=f"ta_{message.id}_{selected_lang}",
                placeholder=f"Enter translation ({selected_lang})…",
                height=120
            )

            if st.button("Save", key=f"save_{message.id}_{selected_lang}"):
                candidate = (st.session_state[draft_key] or "").strip()
                if not candidate:
                    st.session_state["overlay_error"] = "Translation is empty."
                    st.rerun()

                err = provider.add_translation(message.id, selected_lang, candidate)
                if err is None:
                    # success -> update UI as if translation exists
                    message.translate[selected_lang] = candidate
                    st.rerun()
                else:
                    st.session_state["overlay_error"] = err
                    st.rerun()


# ---------------- Demo provider ----------------

class DemoProvider:
    def __init__(self, messages: List[Message]):
        self._messages = {m.id: m for m in messages}

    def get_image(self, image_id: int) -> bytes:
        # Tiny valid 1x1 PNG for offline demo
        import base64
        return base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMB/6Xf1n8AAAAASUVORK5CYII="
        )

    def go_to(self, message_id: int) -> None:
        # Just store target; real app can scroll/jump/load branch/etc.
        st.session_state["goto_message_id"] = message_id
        st.query_params["goto"] = str(message_id)
        st.rerun()

    def add_translation(self, message_id: int, lang: str, translation: str) -> Optional[str]:
        # Demo "DB write" simulation
        # Return string on error to demonstrate overlay
        if len(translation) > 5000:
            return "Translation is too long."
        msg = self._messages.get(message_id)
        if not msg:
            return f"Message {message_id} not found."
        # Simulate success
        msg.translate[lang] = translation
        return None


# ---------------- App ----------------

st.set_page_config(layout="wide")
show_overlay_error_if_any()

# Demo JSON input
example = [
    {
        "id": 1,
        "image_id": 7,
        "text": "Привет. Это текст.",
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
    }
]

raw = st.text_area("Messages JSON (list)", value=json.dumps(example, ensure_ascii=False, indent=2), height=240)

if st.button("Render"):
    payload = json.loads(raw)
    messages = parse_messages(payload)

    # Keep "extra languages" only in-memory for this session (temporary dict/set)
    st.session_state.setdefault("extra_langs", set())

    # --- Two-row "table" (no title, no global columns) ---
    # Row 1: translation select + add language
    with st.container():
        base_langs = collect_available_langs(messages)
        all_langs = ["<None>"] + sorted(set(base_langs) | set(st.session_state["extra_langs"]))

        # default is <None>
        current = st.session_state.get("selected_lang", "<None>")
        if current not in all_langs:
            current = "<None>"
            st.session_state["selected_lang"] = current

        c1, c2, c3, c4 = st.columns([3, 2, 2, 5], vertical_alignment="center")

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
                if st.button("Save language", key="save_lang_btn"):
                    nl = (new_lang or "").strip()
                    if not nl:
                        st.session_state["overlay_error"] = "Language name is empty."
                        st.rerun()
                    st.session_state["extra_langs"].add(nl)
                    st.session_state["selected_lang"] = nl
                    st.session_state["adding_lang"] = False
                    st.rerun()

        # c4 left empty intentionally (to keep row compact)

    # Row 2: render messages in order
    with st.container():
        provider = DemoProvider(messages)
        chosen = st.session_state.get("selected_lang", "<None>")
        selected_lang: Optional[str] = None if chosen == "<None>" else chosen

        for m in messages:
            render_message(m, selected_lang, provider)
            # lightweight separator
            st.markdown("<hr style='opacity:0.25'/>", unsafe_allow_html=True)

# Debug: where go_to points
if "goto_message_id" in st.session_state:
    st.caption(f"goto_message_id = {st.session_state['goto_message_id']}")
