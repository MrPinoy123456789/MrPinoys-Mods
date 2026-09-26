import './style.css'
import { App } from './ui/app'

const app = new App()
void app.start()
;(window as unknown as { roomEditor: App }).roomEditor = app
