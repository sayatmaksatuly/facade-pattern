"""Generate matching editable PlantUML and SVG UML views with no external packages.

Run: python docs/generate_uml.py
The SVG uses a fixed readable layout; both outputs come from this class model.
"""
from pathlib import Path
from html import escape

HERE = Path(__file__).resolve().parent
W, H = 1500, 1510
classes = {
    "ParkingConsole": (575, 65, 350, 100, "client", ["run menu and commands", "ParkingRequest -> Workflow"]),
    "GarageRuntime": (30, 65, 350, 100, "composition root", ["construct and inject dependencies", "+ parking() / administration()"]),
    "ParkingFacade": (255, 200, 440, 130, "workflow facade", ["+ park(request) : Workflow", "+ resume(id) : Workflow", "+ compensate(id, reason) : Workflow", "+ prepareAfterA(request) : Workflow"]),
    "AdministrationFacade": (805, 200, 440, 130, "administrative facade", ["+ inspect() / history(id)", "+ recoverAll() / recover(id)", "+ cancel(id) / checkpoint(request)", "+ demonstrateStandalone(subsystem, id)"]),
    "GateController": (60, 455, 390, 120, "subsystem API", ["+ hold(id, plate, heightCm)", "+ admit(id) / cancel(id)", "+ find(id) / all()"]),
    "LicensePlateReader": (60, 630, 390, 100, "pure business rules", ["+ read(rawPlate, heightCm) : String", "plate validation and clearance <= 220 cm"]),
    "GateGateway": (60, 800, 390, 120, "own persistence gateway", ["+ hold(admission)", "+ admit(id) / cancel(id)", "+ find(id) / all()"]),
    "GateAdmission": (60, 990, 390, 130, "entity record", ["operationId, plate, heightCm, status", "+ admit() / cancel()", "Status: HELD, ADMITTED, CANCELLED"]),
    "ReservationManager": (555, 455, 390, 120, "subsystem API", ["+ reserve(id, space)", "+ occupy(id) / release(id)", "+ find(id) / all() / setSpaceBlocked(...)"]),
    "SpaceSensorGrid": (555, 630, 390, 100, "pure business rules", ["+ validateSpace(space)", "+ requireAvailable(space, blocked, allocated)"]),
    "ReservationGateway": (555, 800, 390, 120, "own persistence gateway", ["+ reserve(reservation, sensorGrid)", "+ occupy(id) / release(id)", "+ setSpaceBlocked(...) / find(id) / all()"]),
    "ParkingReservation": (555, 990, 390, 130, "entity record", ["operationId, space, status", "+ occupy() / release()", "Status: RESERVED, OCCUPIED, RELEASED"]),
    "PaymentTerminal": (1050, 455, 390, 120, "subsystem API", ["+ charge(...) / authorize(...)", "+ capture(id) / refund(id)", "+ find(id) / all()"]),
    "OverstayAlert": (1050, 630, 390, 100, "pure business rules", ["+ priceCents(hours) / requireSufficientTender(...)", "+ overdueMinutes(prepaidHours, elapsedMinutes)"]),
    "PaymentGateway": (1050, 800, 390, 120, "own persistence gateway", ["+ save(payment, capture)", "+ capture(id) / refund(id)", "+ find(id) / all()"]),
    "ParkingPayment": (1050, 990, 390, 130, "entity record", ["operationId, hours, tenderCents, amountCents, status", "+ capture() / refund()", "Status: AUTHORIZED, CAPTURED, REFUNDED"]),
    "WorkflowStore": (60, 1240, 470, 125, "durable workflow journal", ["+ createOrLoad(request) / get(id)", "+ transition(...) / event(...) / events(id)", "Workflow -> ParkingRequest, WorkflowState", "WorkflowEvent: ordered persisted event"]),
    "Database": (775, 1240, 570, 125, "technical infrastructure", ["+ transaction(SqlWork<T>) : T", "+ close()", "JDBC Connection, SQLite file", "one transaction per gateway operation"]),
}
# Solid associations are stored collaborators; dashed dependencies are use/create.
relations = [
    ("ParkingConsole", "ParkingFacade", "association"),
    ("ParkingConsole", "AdministrationFacade", "association"),
    ("GarageRuntime", "ParkingFacade", "association"),
    ("GarageRuntime", "AdministrationFacade", "association"),
    ("GarageRuntime", "Database", "association"),
    ("AdministrationFacade", "ParkingFacade", "association"),
]
for facade in ("ParkingFacade", "AdministrationFacade"):
    for target in ("GateController", "ReservationManager", "PaymentTerminal", "WorkflowStore"):
        relations.append((facade, target, "association"))
for api, policy, gateway, entity in [
    ("GateController", "LicensePlateReader", "GateGateway", "GateAdmission"),
    ("ReservationManager", "SpaceSensorGrid", "ReservationGateway", "ParkingReservation"),
    ("PaymentTerminal", "OverstayAlert", "PaymentGateway", "ParkingPayment"),
]:
    relations.extend([(api, policy, "association"), (api, gateway, "association"),
                      (gateway, entity, "dependency"), (gateway, "Database", "association")])
relations.extend([("ReservationGateway", "SpaceSensorGrid", "dependency"),
                  ("WorkflowStore", "Database", "association")])

puml = ["@startuml", "title Smart Parking Garage Assignment 4", "skinparam classAttributeIconSize 0"]
packages = {"Subsystem A gate": ["GateController", "LicensePlateReader", "GateGateway", "GateAdmission"],
            "Subsystem B spaces": ["ReservationManager", "SpaceSensorGrid", "ReservationGateway", "ParkingReservation"],
            "Subsystem C payment": ["PaymentTerminal", "OverstayAlert", "PaymentGateway", "ParkingPayment"]}
grouped = {name for group in packages.values() for name in group}
def puml_class(name):
    info = classes[name]
    return [f'class {name} <<{info[4]}>> {{', *["  " + m for m in info[5]], "}"]
for name in classes:
    if name not in grouped:
        puml.extend(puml_class(name))
for package, members in packages.items():
    puml.append(f'package "{package}" {{')
    for name in members:
        puml.extend(puml_class(name))
    puml.append("}")
for source, target, kind in relations:
    puml.append(f'{source} {"-->" if kind == "association" else "..>"} {target}')
puml.extend(["note bottom of WorkflowStore", "Workflow, ParkingRequest, WorkflowState and WorkflowEvent", "are supporting value types; entities enforce their own transitions.", "end note", "@enduml"])
(HERE / "uml.puml").write_text("\n".join(puml) + "\n", encoding="utf-8")

svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
       '<defs><marker id="arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0 0 L8 4.5 L0 9" fill="none" stroke="#465466" stroke-width="1.4"/></marker></defs>',
       '<rect width="1500" height="1510" fill="white"/>',
       '<style>text{font-family:Arial,sans-serif;fill:#17212e}.title{font-size:25px;font-weight:bold}.name{font-size:20px;font-weight:bold}.stereo{font-size:14px;fill:#526172}.member{font-size:14px}.label{font-size:14px;fill:#465466}</style>',
       '<text x="750" y="35" text-anchor="middle" class="title">Smart Parking Garage UML class diagram</text>']
for x, label, fill in [(30, "Subsystem A  gate", "#f3f7fb"), (525, "Subsystem B  spaces", "#f3f8f4"), (1020, "Subsystem C  payment", "#fcf8f0")]:
    svg += [f'<rect x="{x}" y="405" width="450" height="755" rx="7" fill="{fill}" stroke="#a6afba"/>',
            f'<text x="{x+225}" y="437" text-anchor="middle" class="name">{label}</text>']

def line(points, dashed=False, label=None, lx=0, ly=0):
    pattern = ' stroke-dasharray="7 5"' if dashed else ''
    svg.append(f'<polyline points="{points}" fill="none" stroke="#465466" stroke-width="1.6" marker-end="url(#arrow)"{pattern}/>')
    if label:
        svg.append(f'<text x="{lx}" y="{ly}" class="label">{escape(label)}</text>')

# Top-level dependencies are routed outside class interiors.
line("670,165 670,180 475,180 475,200")
line("830,165 830,180 1025,180 1025,200")
line("205,165 205,185 280,185 280,200", False, "creates and injects", 35, 180)
line("205,65 205,50 1280,50 1280,225 1245,225", False)
line("805,270 695,270", False, "uses", 735, 260)
# Each facade depends independently on all three subsystem APIs.
for source_y, start_x, rail_y in [(330, 430, 365), (330, 1100, 385)]:
    for target_x in (255, 750, 1245):
        end_x = target_x - 160 if start_x == 430 else target_x + 160
        line(f"{start_x},{source_y} {start_x},{rail_y} {end_x},{rail_y} {end_x},455")
for x in (60, 555, 1050):
    center = x + 195
    line(f"{center},575 {center},630")
    line(f"{x+20},575 {x-13},575 {x-13},850 {x},850", False, "uses", x-4, 780)
    line(f"{center},920 {center},990", True, "persists", center+9, 955)
line("900,800 965,800 965,690 945,690", True)
# Journal and database relations are outside all subsystem boxes.
line("255,255 15,255 15,1290 60,1290", False, "journal", 18, 1210)
line("1245,290 1485,290 1485,1190 40,1190 40,1315 60,1315")
for x in (60, 555, 1050):
    exit_x = x + 390
    rail_x = exit_x + 20
    line(f"{exit_x},875 {rail_x},875 {rail_x},1215 1060,1215 1060,1240")
line("530,1300 775,1300", False, "uses", 630, 1288)

line("30,115 5,115 5,1388 1070,1388 1070,1365")

for name, (x, y, width, height, stereotype, members) in classes.items():
    svg += [f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="3" fill="white" stroke="#465466" stroke-width="1.6"/>',
            f'<text x="{x+width/2}" y="{y+19}" text-anchor="middle" class="stereo">&#171;{escape(stereotype)}&#187;</text>',
            f'<text x="{x+width/2}" y="{y+43}" text-anchor="middle" class="name">{name}</text>',
            f'<path d="M{x} {y+51} H{x+width}" stroke="#a6afba"/>']
    for index, member in enumerate(members):
        svg.append(f'<text x="{x+11}" y="{y+70+index*17}" class="member">{escape(member)}</text>')
svg += ['<text x="60" y="1420" class="member">Solid arrow: stored collaborator association. Dashed arrow: use or create dependency. Arrow points to the dependency.</text>',
        '<text x="60" y="1447" class="member">Each subsystem has four top-level classes and a nested entity Status enum. No subsystem depends on another subsystem.</text>',
        '<text x="60" y="1474" class="member">Supporting value types: ParkingRequest, Workflow, WorkflowState, WorkflowEvent. DomainException and StoreException are technical exceptions.</text>', '</svg>']
(HERE / "uml.svg").write_text("\n".join(svg) + "\n", encoding="utf-8")
print("Generated docs/uml.puml and docs/uml.svg from one class model.")



