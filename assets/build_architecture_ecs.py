"""architecture.svg(원본)에서 EC2 부분만 VPC · ALB · ECS · RDS로 바꿔 architecture-ecs.svg를 만든다.

- 프론트(왼쪽)는 0.8배로 줄이고 백엔드를 왼쪽으로 당긴다.
- 원본 아이콘·문구·배치를 최대한 그대로 쓴다.

실행: python3 assets/build_architecture_ecs.py
"""
import re
from pathlib import Path

HERE = Path(__file__).parent
ORIG = (HERE / "architecture.svg").read_text()
s = ORIG

FE_SCALE = 0.8
BE_X = 185  # 백엔드 그룹 x 이동량 (원본 420)
W, H = 2470, 1290

VPC, VPC_BG = "#8C4FFF", "none"
ALB_BG = "none"
ECS, ECS_BG = "#ED7100", "none"
RDS, RDS_BG = "#4169E1", "none"
MON_BG = "none"
CW, CW_BG = "#E7157B", "#fff"
INK, SUB = "#232F3E", "#5F6B7A"


def one(a, b):
    global s
    assert s.count(a) == 1, (s.count(a), a[:80])
    s = s.replace(a, b)


def cut(start_marker, end_marker, new):
    """start_marker부터 end_marker 직전까지를 new로 바꾼다."""
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    assert a >= 0 and b > a, start_marker[:60]
    s = s[:a] + new + s[b:]


def t(x, y, txt, size=13, weight=400, fill=SUB, anchor="middle"):
    return f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{txt}</text>'


def head(x, y, color):
    return f'<path d="M{x - 1},{y + 4} Q{x - 1},{y - 1} {x + 4},{y - 1} H{x + 34} V{y + 34} H{x - 1} Z" fill="{color}"/>'


WHALE = ORIG[ORIG.find('<g transform="translate(941.5,401) scale(1.05)">'):]
WHALE = WHALE[:WHALE.find('<g transform="translate(1027.0,440.0)')]  # 원본 Application 헤더 고래 (941.5, 401)
SPRING = ORIG[ORIG.find('<g transform="translate(1027.0,440.0)'):]
SPRING = SPRING[:SPRING.find('<text x="1062" y="546"')]  # (1027, 440) 70px
LB = re.search(r'<svg x="2010" y="1070" width="60" height="60" viewBox="0 0 64 64">.*?</svg>', ORIG).group(0)


def moved(markup, ox, oy, x, y, k=1.0):
    return f'<g transform="translate({x - ox * k:.2f},{y - oy * k:.2f}) scale({k})">{markup}</g>'


# ---------- 배포 문구 ----------
one('<rect x="1397" y="200.5" width="355" height="21.5" fill="#fff"/><text x="1575" y="214" font-size="13.5" font-weight="400" fill="#232F3E" text-anchor="middle">러너 IP SSH 임시 허용 → SCP · docker compose up</text>',
    '<rect x="1505" y="200.5" width="140" height="21.5" fill="#fff"/><text x="1575" y="214" font-size="13.5" font-weight="400" fill="#232F3E" text-anchor="middle">ECR push → ECS 배포</text>')

# ---------- EC2 박스 → VPC ----------
one('<rect x="612" y="340" width="900" height="450" rx="4" fill="none" stroke="#ED7100" stroke-width="2"/><path d="M611,344 Q611,339 616,339 H646 V374 H611 Z" fill="#ED7100"/>',
    f'<rect x="612" y="340" width="900" height="450" rx="4" fill="{VPC_BG}" stroke="{VPC}" stroke-width="2"/>{head(612, 340, VPC)}')
one('>EC2 t3.small · Docker Compose<', '>VPC<')
cut('<g transform="translate(629,357) scale(0.6)">', '<rect x="643" y="397"',
    '<g transform="translate(629,357)" fill="none" stroke="#fff" stroke-width="2.2"><rect x="-8" y="-3" width="16" height="12" rx="2"/><path d="M-5 -3 V-7 A5 5 0 0 1 5 -7 V-3"/></g>')

# ---------- Caddy → ALB (아이콘만) ----------
cut('<rect x="643" y="397" width="250" height="200"', '<rect x="937" y="397"',
    LB.replace('x="2010" y="1070" width="60" height="60"', 'x="675" y="467" width="60" height="60"')
    + t(705, 552, "ALB", 15, 600, INK))

# ---------- Application → ECS (Cluster > Service > 컨테이너 2개, ALB에서 갈라짐) ----------
def row(y):
    return (moved(SPRING, 1027, 440, 885, y, 0.6)
            + t(937, y + 19, "Spring Boot", 15, 600, INK, "start") + t(937, y + 39, "Java 21 · REST API", 13, 400, SUB, "start"))


def fan(y):
    return f'<path d="M739,497 L879,{y}" fill="none" stroke="#545B64" stroke-width="1.8" marker-end="url(#ah-545B64)"/>'


cut('<rect x="937" y="397" width="250" height="200"', '<rect x="1231" y="397"',
    f'<rect x="790" y="380" width="403" height="232" rx="4" fill="none" stroke="{ECS}" stroke-width="2"/>{head(790, 380, ECS)}'
    + moved(WHALE, 941.5, 401, 794.5, 384) + t(836, 403, "ECS Cluster", 16, 600, INK, "start")
    + '<rect x="830" y="420" width="343" height="176" rx="4" fill="none" stroke="#1D8102" stroke-width="1.5" stroke-dasharray="6 4"/>'
    + t(1001, 440, "Target Group", 13, 600, "#1D8102")
    + row(452) + row(527) + fan(470) + fan(545))

one('<path d="M1062,397 L1062,372', '<path d="M1062,420 L1062,372')
one('<path d="M1062,637 L1062,603"', '<path d="M1062,637 L1062,600"')

# ---------- Database → RDS ----------
one('<rect x="1231" y="397" width="250" height="200" rx="4" fill="none" stroke="#4169E1" stroke-width="2"/>',
    f'<rect x="1231" y="397" width="250" height="200" rx="4" fill="{RDS_BG}" stroke="{RDS}" stroke-width="2"/>')
one('>Database<', '>RDS<')
cut('<g transform="translate(1235.5,401) scale(1.05)">', '<svg x="1314" y="430"',
    '<g transform="translate(1247.5,413.5)" fill="none" stroke="#fff" stroke-width="2"><ellipse cx="0" cy="-5" rx="7" ry="3"/><path d="M-7 -5 V5 A7 3 0 0 0 7 5 V-5"/></g>')

# ---------- Monitoring ----------
one('<rect x="937" y="637" width="250" height="131" rx="4" fill="none" stroke="#ED7100" stroke-width="2"/>',
    f'<rect x="937" y="637" width="250" height="131" rx="4" fill="{MON_BG}" stroke="#ED7100" stroke-width="2"/>')
one('<rect x="1225" y="676.5" width="129" height="21.5" fill="#fff"/><text x="1290" y="690" font-size="13.5" font-weight="400" fill="#232F3E" text-anchor="middle">VM · DB · 앱 지표</text>',
    f'<rect x="1225" y="676.5" width="129" height="21.5" fill="#fff"/><text x="1290" y="690" font-size="13.5" font-weight="400" fill="#232F3E" text-anchor="middle">DB · 앱 지표</text>')

# ---------- 화살표 (ALB → ECS → RDS, 외부 호출) ----------
cut('<path d="M893,497 L933,497"', '<path d="M1187,497', '')
one('<path d="M1187,497 L1227,497"', '<path d="M1193,497 L1227,497"')
one('<path d="M937,577 L915,577 L915,990 L700,990 L700,1064"', '<path d="M915,596 L915,990 L700,990 L700,1064"')
one('<path d="M915,990 L950,990 L950,1064"', '<path d="M915,990 L950,990 L950,1064"')

# ---------- Sentinel 제거 (ECS에는 호스트가 없다. 장애 알림은 Grafana Cloud가 맡는다) ----------
cut('<rect x="643" y="690"', '<path d="M640,791', '')
# Alloy(사이드카)는 앱 지표만 모은다 → Monitoring에서 RDS로 가는 'DB 지표' 화살표 제거
cut('<path d="M1187,698', '<rect x="1590.0" y="410.0"', '')

# ---------- 백엔드 이동, 프론트 축소 ----------
one('<g transform="translate(420,0)">', f'<g transform="translate({BE_X},0)">')
fe_start = s.find('<g transform="translate(82,78)"')
fe_end = s.rfind('</svg>')
fe = s[fe_start:fe_end]
# 프론트 → 백엔드로 넘어가는 화살표 3개는 지우고 아래에서 절대 좌표로 다시 그린다
a = fe.find('<path d="M804,497 L1061,497"')
b = fe.find('<rect x="440" y="770"')
fe = fe[:a] + fe[b:]

k = FE_SCALE
alb_left, alb_cx, alb_bottom = 675 + BE_X, 705 + BE_X, 527
google_left = 669 + BE_X


def arrow(d):
    return f'<path d="{d}" fill="none" stroke="#545B64" stroke-width="1.8" marker-end="url(#ah-545B64)"/>'


def tag(x, y, txt, w):
    return f'<rect x="{x - w / 2}" y="{y - 13.5}" width="{w}" height="21.5" fill="#fff"/>' + t(x, y, txt, 13.5, 400, INK)


links = (
    arrow(f"M{804 * k:.0f},{497 * k:.0f} L{alb_left - 4},{497 * k:.0f}") + tag(740, 398, "HTTPS", 52)
    + arrow(f"M{226 * k:.0f},{670 * k:.0f} L{alb_cx},{670 * k:.0f} L{alb_cx},{alb_bottom + 4}") + tag(470, 536, "HTTPS", 52)
)
# ALB가 y 397~597이고 프론트 화살표는 y 398에 오므로 ALB 왼쪽 위에 닿는다 → ALB 중앙 높이로 꺾는다
links = (
    arrow(f"M{804 * k:.0f},{497 * k:.0f} L{alb_left - 55},{497 * k:.0f} L{alb_left - 55},485 L{alb_left - 4},485") + tag(735, 398, "HTTPS", 52)
    + arrow(f"M{226 * k:.0f},{670 * k:.0f} L{alb_left - 40},{670 * k:.0f} L{alb_left - 40},512 L{alb_left - 4},512") + tag(470, 536, "HTTPS", 52)
    + arrow(f"M{190 * k:.0f},{950 * k:.0f} L{190 * k:.0f},1100 L{google_left - 4},1100") + tag(470, 1100, "팝업 로그인 · 인가 코드 받기", 190)
)

s = s[:fe_start] + f'<g transform="scale({k})">{fe}</g>' + links + s[fe_end:]
s = s.replace('width="2700" height="1290" viewBox="0 0 2700 1290"', f'width="{W}" height="{H}" viewBox="0 0 {W} {H}"', 1)
s = s.replace('<rect width="2700" height="1290" fill="#fff"/>', f'<rect width="{W}" height="{H}" fill="#fff"/>', 1)

(HERE / "architecture-ecs.svg").write_text(s)
print("architecture-ecs.svg", len(s))
