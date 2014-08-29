/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (props, at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.impl.osm;

import org.opentripplanner.openstreetmap.model.OSMWithTags;

public class NoteProperties {

    public String notePattern;

    public NoteProperties(String notePattern) {
		this.notePattern = notePattern;
	}

	public NoteProperties() {
		this.notePattern = null;
	}

	public String generateNote(OSMWithTags way) {
        return TemplateLibrary.generate(notePattern, way);
    }
}
