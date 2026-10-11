from pathlib import Path
r=Path.cwd()
p=r/'client/flutter_ui/lib/pages/chat_page.dart'
s=p.read_text(encoding='utf-8')
a=s.index('          conversationId:\n',s.index('        MessagePhase('));b=s.index('              : () async {',a)
s=s[:a]+'''          conversationId: isLatest ? ref.read(activeConversationIdProvider) : null,
          onResume: !isLatest ? null'''+s[b:];p.write_text(s,encoding='utf-8')
p=r/'client/flutter_ui/lib/widgets/enhanced_cards.dart';s=p.read_text(encoding='utf-8');a=s.index('                    child: Text(\n',s.index('                  Icon(statusIcon'));b=s.index('\n                  ),',a)
s=s[:a]+'''                    child: AnimatedSwitcher(
                      duration: Duration(milliseconds: MediaQuery.disableAnimationsOf(context) ? 0 : 180),
                      child: Text(
                        '$statusLabel · $toolLabel${searchQuery == null ? '' : ' · $searchQuery'}',
                        key: ValueKey('$statusLabel:$toolLabel'),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 13, color: StudyPalette.of(context).textGray),
                      ),
                    ),'''+s[b:];p.write_text(s,encoding='utf-8')
p=r/'client/flutter_ui/test/chat_upgrade_stage1_test.dart';s=p.read_text(encoding='utf-8').replace('complete text','live complete text');p.write_text(s,encoding='utf-8')
