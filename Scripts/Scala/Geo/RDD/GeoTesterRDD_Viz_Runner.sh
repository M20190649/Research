#!/bin/bash

N=5
MU=3
EPSILON=45
CORES=108
#PS=( $((1*CORES)) $((2*CORES)) $((3*CORES)) $((4*CORES)) )
#QS=( $((1*CORES)) $((2*CORES)) $((3*CORES)) $((4*CORES)) )
CS=( 50 100 150 200 250 300 )
FS=( 0.025 )

for n in `seq 1 $N`; do
    for C in ${CS[@]}; do
	for F in ${FS[@]}; do
	    echo "./GeoTesterRDD_Viz.sh $EPSILON $C $F"
	    ./GeoTesterRDD_Viz.sh $EPSILON $C $F
	done
    done
done

