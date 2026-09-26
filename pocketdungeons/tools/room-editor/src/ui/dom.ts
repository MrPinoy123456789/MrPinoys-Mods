type Attrs = Record<string, string | number | boolean | EventListener | undefined | null>

/** Tiny element builder: h('button', { class: 'btn', onclick: fn }, 'Save'). */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: (Node | string | null | undefined | false)[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v as EventListener)
    else if (k === 'class') el.className = String(v)
    else if (k === 'value' && 'value' in el) (el as HTMLInputElement).value = String(v)
    else if (k === 'checked' && 'checked' in el) (el as HTMLInputElement).checked = Boolean(v)
    else if (v === true) el.setAttribute(k, '')
    else el.setAttribute(k, String(v))
  }
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue
    el.append(typeof c === 'string' ? document.createTextNode(c) : c)
  }
  return el
}

export function $(id: string): HTMLElement {
  const el = document.getElementById(id)
  if (!el) throw new Error(`missing #${id}`)
  return el
}

/** A small modal; resolves with the form values on OK, or null on cancel. */
export function modal(title: string, body: HTMLElement, okLabel = 'OK'): Promise<boolean> {
  return new Promise(resolve => {
    const back = h('div', { class: 'modal-back' })
    const close = (v: boolean) => { back.remove(); resolve(v) }
    const box = h('div', { class: 'modal' },
      h('h3', {}, title),
      body,
      h('div', { class: 'actions' },
        h('button', { class: 'btn', onclick: () => close(false) }, 'Cancel'),
        h('button', { class: 'btn primary', onclick: () => close(true) }, okLabel)))
    back.append(box)
    back.addEventListener('keydown', e => {
      if (e.key === 'Escape') close(false)
    })
    document.body.append(back)
    const first = body.querySelector('input,select,textarea') as HTMLElement | null
    first?.focus()
  })
}
