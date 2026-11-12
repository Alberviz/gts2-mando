# AGENTS.md — guía para agentes en gts2-mando

App Android mínima de uso personal: publica una sesión de música falsa para que el reloj (Amazfit GTS 2 + Zepp) sirva de mando
de tres botones (creatina, ping, comida). Cómo compilar, instalar y configurar: `README.md`. Planes y notas técnicas: `docs/plans/`.
Los servidores a los que llama (hub de Alberto) no están en este repo.

- Es un proyecto personal: no hay tests automáticos; comprueba que `./gradlew assembleDebug` compila antes de dar algo por hecho.
- No guardes tokens ni URLs privadas en el repo: `hubToken` y `pingToken` van en los ajustes de la app.

## Tareas en Olimpo (única fuente, desde noviembre de 2026)

Las tareas, el roadmap y lo que hacen los agentes viven en **Olimpo** (la app de Alberto en la Pi). El vault de
Obsidian, las carpetas `Backlog/`, `vault/🎯 Hoy.md`, `tasks-index`, el widget de tareas y la «bitácora de sesiones»
quedan **obsoletos**: no crees notas de tarea ni escribas bitácoras ahí. Si encuentras una nota de tarea antigua, no la toques.

- **Proyecto:** `gts-2`, subproyecto `gts2-mando` (`olimpo --proyecto gts-2 --sub gts2-mando …`). Las tareas del reloj en general (esferas, notas) van al proyecto `gts-2` sin `--sub`.
- **Antes de crear, busca:** `olimpo buscar "<palabras>"`. Para crear, sigue `olimpo plantillas`: elige el tipo
  (`error`, `funcionalidad`, `investigacion`, `recado`), escribe sus secciones y un resumen vivo:
  `olimpo tarea crear --tipo funcionalidad --resumen "…" --notas "…" "Título"`. Si responde con `parecidas`, díselo a Alberto.
- **Tarea = lo que Alberto pide** («apunta…», «añade una tarea…»). Lo que sea «para el futuro» va al Desván como idea
  (`olimpo idea --help`). No inventes tareas.
- **Al empezar:** `olimpo tarea empezar <id>`. **Al terminar:** `olimpo tarea hecha <id>`. Los hallazgos van como
  aportes (`olimpo tarea aportar <id> "…"`) y el resumen se reescribe con `olimpo tarea resumir`. Nunca borres tareas.
- **«Al foco» / «para hoy»:** el foco del día son las tareas que Alberto ve primero en Inicio. La tarea debe existir ya
  en Olimpo. `olimpo foco` lo muestra y `olimpo foco --fijar <id> <id>…` lo **reemplaza entero**: léelo antes y pasa los
  ids que ya había más el nuevo. No lo confundas con `olimpo tarea empezar`, que solo cambia el estado a `en_curso`.
- **Lo que has hecho en la sesión:** un aporte en la tarea en la que trabajaste (qué hiciste, qué falta, commits). Es
  lo que Alberto lee al volver; sustituye a la bitácora.
- Roadmap: `olimpo roadmap`; versiones: `olimpo version`. Si Alberto menciona una tarea de OTRO proyecto, créala en
  el suyo con `--proyecto <slug>`.
- **Firma:** los agentes firman como `claude-pc` (la variable `OLIMPO_TOKEN_FILE` del entorno). Si en el historial sale
  «laura», falta esa variable: avisa a Alberto; no uses `~/.config/olimpo/cli.json`, que es de Laura.
