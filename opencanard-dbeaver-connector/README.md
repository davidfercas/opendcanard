# OpenCanard connector for DBeaver

A small DBeaver plugin that adds **OpenCanard** (with the logo from `resources/`) to the *New Database
Connection* dialog. It is a Generic JDBC data source with the OpenCanard JDBC driver preconfigured:

| Setting | Value |
| --- | --- |
| Driver class | `io.opencanard.driver.jdbc.OpenCanardDriver` |
| URL template | `jdbc:opencanard://{host}[:{port}]` |
| Default port | `8815` |

> The plugin could not be tested against a running DBeaver when it was written (see
> [If it does not show up](#if-it-does-not-show-up)). The icons are generated from
> `resources/opencanard_logo.png` (the duck and database, transparent background): `icons/opencanard_icon.png`
> (16x16) and `icons/opencanard_icon_big.png` (64x64).

## Build

1. Build the driver (it is packed into the plugin):

   ```powershell
   mvn -pl opencanard-jdbc-driver -am clean package
   ```

2. Build the plugin, from this folder (needs a JDK on the PATH):

   ```powershell
   .\build.ps1        # Windows
   ./build.sh         # Linux / macOS
   ```

   The result is `target/opencanard-dbeaver-connector_0.1.0.jar`. Without the driver jar from step 1 the
   plugin is built without it and you add the jar by hand in DBeaver (see below).

## Install

1. Close DBeaver.
2. Copy `opencanard-dbeaver-connector_0.1.0.jar` to the `dropins` folder of the DBeaver installation, for example
   `C:\Program Files\DBeaver\dropins\`.
3. Start DBeaver once with a clean plugin cache: `dbeaver.exe -clean`.
4. *Database > New Database Connection*, search for **OpenCanard**.

After a new version of the plugin or the driver, repeat the steps (the `-clean` start is needed again).

## If it does not show up

- Check *Help > About DBeaver > Installation Details > Plug-ins* for `org.opencanard.dbeaver.connector`. If it is
  missing, the jar is not in the right `dropins` folder.
- If the plugin is listed but there is no OpenCanard entry, look in `<workspace>/.metadata/.log`
  (*Help > Show Log*, or `%APPDATA%\DBeaverData\workspace6\.metadata\.log`) for errors mentioning
  `org.opencanard` and send them: the cause is usually the `plugin.xml` format of that DBeaver version.
- If DBeaver says the driver file is missing, open *Database > Driver Manager > OpenCanard > Libraries*, press
  *Add File* and pick `opencanard-jdbc-driver-0.1-all.jar` (use the `-all` jar, not the `original-` one).

Without the plugin you can still connect: create a driver by hand in *Driver Manager > New* with the class and
URL above. DBeaver does not let a manually created driver have a custom icon, which is why this plugin exists.
