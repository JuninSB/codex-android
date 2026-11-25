package local.codex.mobile.ui;

import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;

final class Strikethrough extends CustomNode {
    static final class Processor implements DelimiterProcessor {
        @Override
        public char getOpeningCharacter() {
            return '~';
        }

        @Override
        public char getClosingCharacter() {
            return '~';
        }

        @Override
        public int getMinLength() {
            return 2;
        }

        @Override
        public int process(DelimiterRun opening, DelimiterRun closing) {
            if (opening.length() != 2 || closing.length() != 2) return 0;
            Node opener = opening.getOpener();
            Node closer = closing.getCloser();
            var strikethrough = new Strikethrough();
            for (Node content = opener.getNext(); content != closer; ) {
                Node next = content.getNext();
                strikethrough.appendChild(content);
                content = next;
            }
            opener.insertAfter(strikethrough);
            return 2;
        }
    }
}
