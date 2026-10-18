# Shopify Receipt Printer

An Android app that automatically prints new Shopify orders on a kitchen receipt printer, so nobody has to watch a screen or press print.

It was built for a bakery business whose shops place wholesale orders with a central kitchen through a Shopify store. Every new order comes out of the kitchen printer as a ticket within about half a minute of being placed.

## What it does

- Watches the Shopify store for new orders, around the clock
- Prints each new order as a clear kitchen ticket
- Marks each printed order in Shopify so it never prints twice, even after a restart
- Catches up on orders placed while the device or internet was down
- Starts by itself when the device is switched on or restarted
- Finds the printer on the network automatically

## What a ticket looks like

Each ticket shows, from top to bottom:

1. The business name and **KITCHEN ORDER** in large print
2. The order number and the date and time it was placed
3. **Which shop ordered it**, in large print, since this is what the kitchen sorts by
4. The requested delivery or pickup date, if the order has one
5. Each item with the **quantity first** in large print, then the product and its options
6. The total number of items
7. Any note left on the order

The paper is then fed and cut, ready to tear off.

## How it works

The app runs quietly in the background on an always-on Android device (such as a POS terminal) that sits on the same WiFi network as the printer.

1. Every 25 seconds or so, the app asks Shopify for any new orders that haven't been printed yet.
2. Each new order is laid out as a ticket and sent straight to the receipt printer over the network.
3. Once the ticket has printed, the app tags the order as `printed` in Shopify. That tag is how it knows not to print the same order again.

Shopify does not offer automatic printing for online orders, so this app acts as the bridge between the store and the kitchen printer.

## Getting started

1. Make sure the receipt printer is switched on, has paper, and is connected to the same WiFi as the Android device.
2. Install the app on the Android device and open it once.
3. A **PRINTER CONNECTED** ticket prints within a minute. That means everything is set up.
4. Place a test order on the store. Its ticket should print within about 30 seconds.

There is nothing else to set up. The app keeps running after you leave it and starts again by itself after a restart or power cut.

## The app screen

Opening the app shows:

- **Status**: whether orders are printing automatically, when the store was last checked, and the last order printed
- **Activity**: a short log of recent prints and any problems
- **Print test ticket**: prints a sample ticket to check the printer
- **Check now**: checks for new orders straight away
- **Find printer**: searches the network for the printer again

## Troubleshooting

| Problem | What to try |
|---|---|
| Nothing printed after installing | Check the printer is on, has paper, and is on the same WiFi. Then press **Find printer**. |
| Orders stopped printing | Open the app and read the message at the top of the status panel. Most problems are the printer being off, out of paper, or off the WiFi. |
| A ticket printed on the wrong printer | Type the correct printer's IP address into the Printer IP field and press **Save settings**. |
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
