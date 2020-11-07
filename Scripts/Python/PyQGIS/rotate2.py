l1 = QgsGeometry.fromWkt("LINESTRING(0 0, 4 0)")
l2 = QgsGeometry.fromWkt("LINESTRING(0 0, 2 3.46410161514)")
p1 = QgsGeometry.fromWkt("POINT(0 0)")
p2 = QgsGeometry.fromWkt("POINT(4 0)")
p3 = QgsGeometry.fromWkt("POINT(2 3.46410161514)")
layer = QgsVectorLayer("LineString", "Arcs", "memory")
crs = layer.crs()
crs.createFromId(3857)
layer.setCrs(crs)
provider = layer.dataProvider()
QgsProject.instance().addMapLayer(layer)

f = QgsFeature()
f.setGeometry(l1)
provider.addFeature(f)
layer.updateExtents()
layer.reload()
points = []
points.append(l1.vertexAt(1))
for i in range(0, 60):
	geom = f.geometry()
	geom.rotate(-1, p1.asPoint())
	points.append(geom.vertexAt(1))
	f.setGeometry(geom)
	provider.addFeature(f)
	layer.updateExtents()
	layer.reload()

g = QgsFeature()
g.setGeometry(l1)
points.append(l1.vertexAt(0))
provider.addFeature(g)
layer.updateExtents()
layer.reload()
for i in range(0, 60):
	geom = g.geometry()
	geom.rotate(1, p2.asPoint())
	points.append(geom.vertexAt(0))
	g.setGeometry(geom)
	provider.addFeature(g)
	layer.updateExtents()
	layer.reload()

h = QgsFeature()
h.setGeometry(l2)
points.append(l2.vertexAt(0))
provider.addFeature(h)
layer.updateExtents()
layer.reload()
for i in range(0, 60):
	geom = h.geometry()
	geom.rotate(-1, p3.asPoint())
	points.append(geom.vertexAt(0))
	h.setGeometry(geom)
	provider.addFeature(h)
	layer.updateExtents()
	layer.reload()

print(len(points))
pointsXY = []
for point in points:
	pointsXY.append(QgsPointXY(point.x(), point.y()))
perimeter = QgsFeature()
geom = QgsGeometry.fromPolyline(points)
perimeter.setGeometry(geom)
provider.addFeature(perimeter)
layer.updateExtents()
layer.reload()
