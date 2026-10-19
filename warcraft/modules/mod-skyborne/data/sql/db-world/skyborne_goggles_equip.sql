-- The goggles are worn in the head slot (class 4, miscellaneous), with their own display (the client's ItemDisplayInfo row).
UPDATE `item_template` SET `class` = 4, `subclass` = 0, `InventoryType` = 1, `displayid` = 98708 WHERE `entry` = 98708;
