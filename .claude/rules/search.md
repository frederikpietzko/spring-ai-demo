<!-- IDE CLI search rules. Written by `ide install`, removed by `ide uninstall`; edits are overwritten. -->
# Searching the project
- File unknown? ONE `ide locate "<one or two sentences: behaviour, symptom, names — quote the task>"`, read one
  returned file by line range (`sed -n a,bp <file>`), then narrow (`ide locate --path <dir of the best hit>`, once).
  Never a second broad `ide locate`; a ranking is no proof of absence. Skip it when the task names the file or class.
- Known name: `ide find-symbol <Name> --source-lines 30`; who uses it: `ide search --word <Name> --enclosing`.
- Text, regex, config key: `ide search --query <text>` (`--regex`, `--ignore-case`, `--path <dir|file>`, `--include <glob>`);
  a file by name: `ide find-files --query <name>`. NOT `grep -rn`, `rg`, `find . -name`, the Grep and Glob tools.
- Footer: `complete` is the whole answer, `limit hit` → narrow with `--path`, `partial` → retry. Session: `IDECLI_SESSION_ID`,
  else `--session-id <id>` from the session line. Before the first `ide locate` the hook denies grep/rg/find over the tree under `IDECLI_SEARCH_BOOTSTRAP=on`.
