# Shopify Receipt Printer

An Android app that automatically prints new Shopify orders on a kitchen receipt printer, so nobody has to watch a screen or press print.

It was built for a bakery business whose shops place wholesale orders with a central kitchen through a Shopify store. Every new order comes out of the kitchen printer as a ticket within about half a minute of being placed.

## What it does

- Watches the Shopify store for new orders, around the clock
- Prints each new order as a clear kitchen ticket
- Marks each printed order in Shopify so it never prints twice, even after a restart
- Checks the printer on every round, and only counts a ticket as printed once the printer confirms it was ready and took the job
- Sounds an alarm on the device if orders stop printing, for example when the printer is off or out of paper
- Catches up on orders placed while the device, printer or internet was down, however long ago. Tickets that print more than 30 minutes after the order was placed are marked **LATE**
- Starts by itself when the device is switched on or restarted
- Finds the printer on the network automatically, and never switches to a different printer without asking

## What a ticket looks like

Each ticket shows, from top to bottom:

1. The business name and **KITCHEN ORDER** in large print
2. The order number and the date and time it was placed, with a **LATE** banner if it is printing well after that
3. **Which shop ordered it**, in large print, since this is what the kitchen sorts by
4. The customer's name, delivery address, phone number, delivery method, and payment method with whether it has been paid
5. The requested delivery or pickup date, if the order has one
6. Each item with the **quantity first** in large print, then the product, its options, the price each and the line total
7. The total number of items, any delivery fee and the order total
8. Any note left on the order, keeping its line breaks

The paper is then fed and cut, ready to tear off.

## How it works

The app runs quietly in the background on an always-on Android device (such as a POS terminal) that sits on the same WiFi network as the printer.

1. Every 25 seconds or so, the app checks the printer is ready and asks Shopify for any new orders that haven't been printed yet.
2. Each new order is laid out as a ticket and sent straight to the receipt printer over the network. The printer is asked for its status before and after each ticket, so a printer that is out of paper, has its cover open, or drops the connection doesn't count as printed.
3. Once the ticket has printed, the app tags the order as `printed` in Shopify. That tag is how it knows not to print the same order again.

If a problem lasts more than a minute, the device sounds an alarm with a message saying what is wrong and how many orders are waiting. The alarm repeats every 10 minutes until the problem is fixed, and the waiting orders print as soon as it is.

Shopify does not offer automatic printing for online orders, so this app acts as the bridge between the store and the kitchen printer.

## Getting started

1. Make sure the receipt printer is switched on, has paper, and is connected to the same WiFi as the Android device.
2. Install the app on the Android device and open it once.
3. A **PRINTER CONNECTED** ticket prints within a minute. It only prints once both the printer and the Shopify store have answered, so it means everything is set up.
4. Place a test order on the store. Its ticket should print within about 30 seconds.

Orders placed before the app was first opened are not printed. If there are any unprinted ones from the past week, the **PRINTER CONNECTED** ticket lists their order numbers under **NOT PRINTED** so they can be checked in Shopify.

If more than one device on the network answers like a printer, the app doesn't guess. It asks you to choose the kitchen printer on its screen, where you can print a test on each one first.

There is nothing else to set up. The app keeps running after you leave it and starts again by itself after a restart or power cut.

## The app screen

Opening the app shows:

- **Choose the kitchen printer**: only appears when the app needs you to pick a printer
- **Status**: whether orders are printing automatically, when the store was last checked, the last order printed, and any problem that needs attention
- **Activity**: a short log of recent prints and any problems
- **Check for orders now**: checks for new orders straight away
- **Print test ticket**: prints a sample ticket to check the printer
- **Printer**: the printer's address, with **Find printer** to search the network again
- **Battery settings**: lets the app keep running in the background

There are no store settings on the screen. Everything the app needs is built in, so there is nothing to set up or accidentally change.

## Troubleshooting

| Problem | What to try |
|---|---|
| Nothing printed after installing | Check the printer is on, has paper, and is on the same WiFi. Then press **Find printer**. |
| The device is sounding an alarm | Orders aren't printing. Tap the alarm to open the app and read the message in the status panel. Most problems are the printer being off, out of paper, its cover open, or off the WiFi. |
| The app asks to choose the kitchen printer | Press **Print test** next to each address, then **Use this printer** on the one whose test came out in the kitchen. If the kitchen printer was just switched off, switching it back on also clears this. |
| A ticket printed on the wrong printer | Type the correct printer's IP address into the Printer IP field and press **Save**. |
| The app stops after a while | In the device's settings, allow the app to run in the background without battery restrictions. The **Battery settings** button in the app goes straight there. |

Orders placed while the printer was off will print as soon as it is back on.

## For developers

Kotlin, minimum Android 9. It uses the Shopify Admin GraphQL API (`read_orders` and `write_orders` scopes) and raw ESC/POS over TCP port 9100.

Store credentials and the release signing key are read from `local.properties` at build time and are never committed. The app needs these keys:

```
SHOPIFY_STORE_DOMAIN=
SHOPIFY_APP_ACCESS_TOKEN=
SHOPIFY_APP_API_VERSION=
TICKET_HEADER=
RELEASE_STORE_FILE=
RELEASE_STORE_PASSWORD=
RELEASE_KEY_ALIAS=
RELEASE_KEY_PASSWORD=
```

Build the release APK with `./gradlew assembleRelease`.

To see how recent orders will print without printing or tagging anything, run:

```
./gradlew :app:testDebugUnitTest --tests "*OrderPreviewTest" -PorderPreview=24
```

`24` is how many hours back to look. It writes the raw Shopify response to `app/build/order-preview/response.json`, and a text picture of each ticket to `tickets.txt` in the same folder.
