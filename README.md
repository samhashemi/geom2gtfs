geom2gtfs
=========

[geom2gtfs](https://github.com/conveyal/geom2gtfs) is a command line tool that converts each feature in a shapefile into a route in a GTFS file. Using the geom2gtfs tool is simple, but it requires a carefully prepared shapefile and configuration file. To run it from the command line:

    $ geom2gtfs shapefile_filename config_filename output_filename

The input shapefile must be unprojected, and contain only lines, with no multigeometry features. If the features in the shapefile have properties, such as “route” or “mode” or “speed” those properties can be used to modulate the speed of the routes written to the GTFS. It’s possible to use multiple lines to represent the same route if they share a route id property (the name of the property is defined in the config file) and sequential lines run in the same direction and have successive “segment” properties. It’s possible to join a CSV to the shapefile using the geom2gtfs config file, which is how we associated service frequencies with lines. The CSV of service frequencies from King County was compiled using the King County spreadsheet, and looks like this:

    route,peak_am,midday,peak_pm,night,sat,sun
    1,15.0,30.0,15.0,45.0,None,None
    10,10.0,15.0,10.0,30.0,15.0,30.0
    101,15.0,30.0,15.0,30.0,30.0,30.0
    102,30.0,None,25.0,None,None,None
    105,30.0,30.0,30.0,30.0,30.0,60.0
    106,15.0,15.0,15.0,45.0,30.0,30.0
    107,30.0,30.0,30.0,45.0,30.0,30.0
    11,15.0,30.0,15.0,45.0,30.0,30.0
    110,None,None,None,None,None,None
    111,22.0,None,25.0,None,None,None
    113,36.0,None,45.0,None,None,None
    114,60.0,None,60.0,None,None,None
    116EX,22.0,None,25.0,None,None,None
    ...

Finally, it’s possible to have the geom2gtfs tool place stops at a regular spacing along the lines, or to use a shapefile of existing stops. In the case of our King County analysis, I produced a shapefile from the stops.txt of the original GTFS and used that. 

### An example geom2gtfs configuration file ###

The config file is a JSON file, which follows with annotations. They aren’t part of the config file.

    {
First, some basic information for the GTFS feed.

      "agency_name":"King County Metro",
      "agency_url":"http://metro.kingcounty.gov/",
      "agency_timezone":"America/Los_Angeles",
Specify which mode each route will be.

      "gtfs_mode":3,
Set the speed of the GTFS trips, in meters per second.

      "speed":[
The speed can be a constant, or a list. If it’s a list, each item in the list must have two items. The first is the filter, and the second is the speed. The filter [“ROUTE”,”12”] will match if the property “ROUTE” takes the value “12”. geom2gtfs scans down the list and uses the first match. So if a shapefile feature had property “ROUTE” with value “12” and “express” with value “1”, that feature’s trips would run at 4.0 meters per second. “*” matches everything, which means that 5.4 is the default speed.

            [["ROUTE","12"],4.0],
            [["ROUTE","2"],4.0],
            [["ROUTE","3"],4.0],
            [["express","1"],13.4],
            [["ROUTE","193EX"],4.0],
            [["express","*"],5.4],
      ],
The ‘stops’ section specifies a strategy, which can be either “shapefile” or “picket”, and some arguments required by either given strategy. The “shapefile” strategy requires an unprojected point shapefile and a threshold around each linear feature to look for stops in that shapefile. The ‘picket’ strategy takes one named argument ‘spacing’, either a scalar or list of filters like the speed argument. 

      "stops":{
             "strategy":"shapefile",
             "filename":"data/kingco/kingco_stops.shp",
             "threshold":0.0002,
      },
Specify the name of the shapefile property where the route id is kept.

      "route_id_prop_name":"ROUTE",
Specify the name of the shapefile property where the route name is kept.

      "route_name_prop_name":"ROUTE",
Specify the ‘service windows’. Each entry in the list specifies the service window name, is starting time, and its ending time, both in hours since midnight. For example “peak_am” runs from 6am to 9am. The shapefile must contain a property with the same name as each service window, filled out with the service level for that frequency. For example the route “1” has a property “peak_am” with value “15.0”.

      "service_windows":[
             ["peak_am",6,9],
             ["midday",9,15],
             ["peak_pm",15,18],
             ["night",18,24],
      ],
Optionally, join a CSV to the shapefile features. In the case of our shapefile, none of the service windows are actually properties of the shapefile features; they are joined in from this shapefile.

      "csv_join":{
        "filename":"data/kingco/prop_freqs.csv",
        "csv_col":"route",
        "shp_col":"ROUTE",
      },
Optionally, set a filter that must pass for a feature to be converted to a GTFS route.

      "filters":[
        ["CATEGORY","topo"],
      ],
Set the start and end date of the service calendar. Our hypothetical GTFS will be valid from the start of 2014 to the start of 2015.

      "start_date":"20140101",
      "end_date":"20150101",
Specify whether the service level values are periods, the amount of time between departures; or frequencies, the number of departures in an hour.

     "use_periods":true,
Set whether or not service should run in both directions of a shapefile line. If “is_bidirectional” is false, you’ll need to make a shapefile feature for each route direction.

     "is_bidirectional":true,
Set whether the GTFS should contain precise times, or whether it should be frequency-based.

      "exact":true
    }

This config file, combined with a shapefile that I manually drew for every one of the fifty revised routes in King County, produced a GTFS representing a reasonable approximation of the realigned routes. That’s only a part of the puzzle though. For the next part, we’ll need resample_gtfs.