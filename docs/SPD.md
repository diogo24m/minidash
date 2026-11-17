# Leitura do carro por SPD

O minidash usa agora o serviço integrado `com.spd.carinfo/.service.CarInfoService`.
A ligação Binder e o modelo foram adaptados de `CarInfo.java` e `Vehicle.java` do
MiniLauncher, em `D:\Trabalhos\MiniLauncher`, mantendo o contrato e as validações
documentados nesse projeto em `docs/carro/README.md`.

O serviço é detetado automaticamente. O interruptor **Dados do carro por SPD**, na
oficina, controla a leitura. Os identificadores antigos de preferências, widgets
e fonte de pneus mantêm-se para preservar as configurações existentes; os
adaptadores Bluetooth, BLE, Wi-Fi e USB OBD guardados já não são usados.

- Velocidade `140062`: perfil BMW MINI `135725059`, km/h, consulta a cada segundo
  e callbacks. Leituras com mais de 3 segundos são descartadas. O GPS serve de
  reserva quando existe permissão e uma leitura GPS disponível.
- Bateria: `Settings.System/SETTING_CURRENT_VOLTAGE`, décimas de volt.
- Temperatura exterior: `30023`, apenas °C.
- Combustível `100045`: litros, apenas BMW MINI. Não é publicado como percentagem.
- Autonomia `100011`, conta-quilómetros `100013`, unidades `120283` e computador
  de bordo `120287`: quilómetros, km/h e L/100 km. O consumo mantém L/100 km
  no barramento, nos mostradores, nos gráficos e nos resumos de viagem.
- Portas `50001`, manutenção `120290` e pneus `120288`: os mesmos campos e
  validações do MiniLauncher. A pressão passa de décimas de bar para kPa.
  A ordem das rodas mantém a hipótese do MiniLauncher e requer confirmação no carro.
- Os campos de regime, mudanças, travões, luzes e nível de óleo permanecem brutos
  em **Diagnóstico SPD**. Não alimentam medidores como leituras confirmadas.

As consultas lentas são feitas a cada 10 segundos e os pneus a cada minuto.
Sem consumidores a leitura termina e os dados CAN são removidos do barramento.
Desativar o módulo termina a ligação imediatamente. A integração usa getters e
subscrições; não chama setters, não apaga avarias nem reinicia manutenção no carro.
O SPD não fornece os DTC, VIN e monitores do antigo diagnóstico OBD; a oficina
apresenta as leituras disponíveis e não afirma que o carro está sem avarias.

Validação local: `gradlew.bat :feature:obd:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubDebug`.
Para validar a ligação real, é necessária uma central com o serviço SPD instalado.
